package lux.xpath;

import java.util.ArrayList;
import java.util.HashMap;

import lux.XPathQuery;
import lux.api.ValueType;
import lux.xpath.PathStep.Axis;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;

/**
 * Prepares an XPath expression tree for indexed execution against a
 * Lux data store.
 *
 * The general strategy here is to consider each expression in isolation,
 * determining whether it imposes any restriction on its context, and then
 * to compose such constraints into queries, to be executed by a searcher, and
 * the XPath/XQuery expressions evaluated against the resulting documents. 
 * 
 * Absolute expressions are targets for optimization; the optimizer attempts to form queries that 
 * retrieve the smallest possible subset of available documents for which the expression generates
 * a non-empty result sequence.  The queries for these sub-expressions are composed
 * according to the semantics of the combining expressions and functions.  Absolute
 * sequences (occurrences of /) generally inhibit query composition.
 */
public class PathOptimizer extends ExpressionVisitorBase {

    private ArrayList<XPathQuery> queryStack;
    //private AbstractExpression expr;
    
    private final String attrQNameField = "lux_att_name_ms";
    private final String elementQNameField = "lux_elt_name_ms";
    
    private static final XPathQuery MATCH_ALL_QUERY = XPathQuery.MATCH_ALL;
    
    public PathOptimizer() {
        queryStack = new ArrayList<XPathQuery>();
        push(MATCH_ALL_QUERY);
    }
    
    /**
     * Prepares an XPath expression tree for indexed execution against a
     * Lux data store.  Inserts calls to lux:search that execute queries
     * selecting a set of documents against which the expression, or some
     * of its sub-expressions, are evaluated.  The queries will always
     * select a superset of the documents that actually contribute results
     * so that the result is the same as if the expression was evaluated
     * against all the documents in collection() as its context (sequence
     * of context items?).
     *
     * @param expr the expression to optimize
     */
    public AbstractExpression optimize(AbstractExpression expr) {
        expr = expr.accept (this);
        return optimizeExpression (expr, 0, 0);
    }
    
    /**
     * @param expr the expression to optimize
     * @param j the query stack depth at which expr's query is to be found
     * @param facts additional facts to apply to the query
     */
    private AbstractExpression optimizeExpression (AbstractExpression expr, int j, int facts) {
        if (expr.isAbsolute()) {
            FunCall search = getSearchCall(j, facts);
            if (search.getReturnType().equals(ValueType.DOCUMENT)) {
                // Avoid the need to sort the results of this expression so that it can be 
                // embedded in a subsequence or similar and evaluated lazily.
                AbstractExpression tail = expr.getTail();
                if (tail != null) {
                    return new Predicate (search, tail);
                }
            }
            expr = expr.replaceRoot (search);
            // rather than wrapping the whole expression in a function call that requires a special
            // iterator (lux:root), let's try submerging the path in a predicate
            /*
            if (search.getReturnType() == ValueType.DOCUMENT) {
                expr = new FunCall(FunCall.luxRootQName, ValueType.DOCUMENT, expr);
            }
            */
        }
        return expr;
    }

    /**
     * TODO: some operations *commute with lux:search*.  In those cases, we do better to wrap the search around
     * this expression, rather than around each of its children.  Basically we want to "pull up" the search operation 
     * to the highest allowable level.
     * 
     * Each absolute subexpression S is joined with a call to lux:search(), 
     * effectively replacing it with search(QS)/S, where QS is the query derived 
     * corresponding to S.  In addition, if S returns documents, the foregoing expression
     * is wrapped by root(); root(search(QS)/S)
     * 
     * @param expr an expression
     * @param facts assertions about the search to be injected, if any.  These are or-ed with 
     * facts found in the search query.
     */
    protected void optimizeSubExpressions(AbstractExpression expr, int facts) {
        AbstractExpression[] subs = expr.getSubs();
        for (int i = 0; i < subs.length; i ++) {
            subs[i] = optimizeExpression (subs[i], subs.length - i - 1, facts);
        }
    }    
    
    public AbstractExpression visit (Root expr) {
        push (MATCH_ALL_QUERY);
        return expr;
    }
    
    // An absolute path is a PathExpression whose left-most expression is a Root.
    // divide the expression tree into regions bounded on the right and left by Root
    // then optimize these absolute paths as searches, and then optimize some functions 
    // like count(), exists(), not() with arguments that are searches
    //
    // also we may want to collapse some path expressions when they return documents;
    // like //a/root().  in this case 
    public AbstractExpression visit(PathExpression pathExpr) {
        XPathQuery rq = pop();
        XPathQuery lq = pop();
        XPathQuery query = combineQueries (lq,  Occur.MUST, rq, Occur.MUST, rq.getResultType());
        push(query);
        return pathExpr;
    }
    
    public AbstractExpression visit(PathStep step) {
        QName name = step.getNodeTest().getQName();
        Axis axis = step.getAxis();
        boolean isMinimal;
        if (axis == Axis.Descendant || axis == Axis.DescendantSelf || axis == Axis.Attribute) {
            isMinimal = true;
        } 
        else if (axis == Axis.Child && getQuery().isFact(XPathQuery.MINIMAL) && ValueType.NODE.is(getQuery().getResultType())) {
            // special case for //descendant-or-self::node()/child::element(xxx)
            isMinimal = true;
        }
        else {
            isMinimal = false;
        }
        XPathQuery query;
        if (name == null) {
            ValueType type = step.getNodeTest().getType();
            if (axis == Axis.Self && (type == ValueType.NODE || type == ValueType.VALUE)) {
                // if axis==self and the type is loosely specified, use the prevailing type
                type = getQuery().getResultType();
            }
            else if (axis == Axis.AncestorSelf && (type == ValueType.NODE || type == ValueType.VALUE)
                    && getQuery().getResultType() == ValueType.DOCUMENT) {
                // FIXME: This is wrong: do we rely on it?
                type = ValueType.DOCUMENT;
            }
            query = XPathQuery.getQuery(new MatchAllDocsQuery(), getQuery().getFacts(), type);
        } else {
            TermQuery termQuery = nodeNameTermQuery(step.getAxis(), name);
            query = XPathQuery.getQuery(termQuery, isMinimal ? XPathQuery.MINIMAL : 0, step.getNodeTest().getType());
        }
       push(query);
       return step;
    }
    
    /**
     * If a function F is emptiness-preserving, in other words F(a,b,c...)
     * is empty ( =()) if *any* of its arguments are empty, and is
     * non-empty if *all* of its arguments are non-empty, then its
     * argument's queries are combined with Occur.MUST.  This is the
     * default case, and such functions are not mapped explicitly in
     * fnArgParity.
     *
     * Otherwise, no optimization is possible, and the argument queries are combined with Occur.SHOULD.
     *
     * count() (and maybe max(), min(), and avg()?) is optimized as a
     * special case.
     * @return 
     */

    public AbstractExpression visit(FunCall funcall) {
        QName name = funcall.getQName();
        // Try special function optimizations, like count(), exists(), etc.
        FunCall luxfunc = optimizeFunCall (funcall);
        if (luxfunc != funcall) {
            return luxfunc;
        }
        // see if the function args can be converted to searches.
        optimizeSubExpressions(funcall, 0);
        Occur occur;
        if (! name.getNamespaceURI().equals (FunCall.FN_NAMESPACE)) {            
            // we know nothing about this function
            occur = Occur.SHOULD;
        }
        // a built-in XPath 2 function
        else if (fnArgParity.containsKey(name.getLocalPart())) {
            occur = fnArgParity.get(name.getLocalPart());
        } else {
            occur = Occur.MUST;
        }
        if (occur == Occur.SHOULD) {
            for (int i = funcall.getSubs().length; i > 0; --i) {
                pop();
            }
            push ( XPathQuery.getQuery(new MatchAllDocsQuery(), 0, ValueType.VALUE));
        } else {
            combineTopQueries(funcall.getSubs().length, occur, funcall.getReturnType());
        }
        return funcall;
    }

    // FIXME: fill out the rest of this table
    protected static HashMap<String, Occur> fnArgParity = new HashMap<String, Occur>();
    static {
        fnArgParity.put("collection", null);
        fnArgParity.put("doc", null);
        fnArgParity.put("uri-collection", null);
        fnArgParity.put("unparsed-text", null);
        fnArgParity.put("generate-id", null);
        fnArgParity.put("deep-equal", null);
        fnArgParity.put("error", null);
        fnArgParity.put("empty", Occur.SHOULD);
        fnArgParity.put("not", Occur.SHOULD);
    };
    
    public FunCall optimizeFunCall (FunCall funcall) {
        AbstractExpression[] subs = funcall.getSubs();
        /*
        if (subs.length == 1 && subs[0] instanceof Dot) {
            // TODO what about count(.), exists(.), empty(.)?
            // what about (/)/root (//foo)?
            if (funcall.getQName().equals(FunCall.rootQName)) {
                return new FunCall (FunCall.luxRootQName, ValueType.DOCUMENT, subs);
            }            
        }
        */
        if (subs.length == 0 || ! subs[0].isAbsolute()) {
            return funcall;
        }
        XPathQuery query = pop();
        // can only use these function optimizations when we are sure that its argument expression
        // is properly indexed
        if (query.isMinimal()) {
            // apply no restrictions to the enclosing scope:
            push (MATCH_ALL_QUERY);
            int functionFacts = 0;
            ValueType returnType = null;
            QName qname = null;
            if (funcall.getQName().equals(FunCall.countQName) && query.getResultType().is(ValueType.DOCUMENT)) {
                functionFacts = XPathQuery.COUNTING;
                returnType = ValueType.INT;
                qname = FunCall.luxCountQName;
            } 
            else if (funcall.getQName().equals(FunCall.existsQName)) {
                functionFacts = XPathQuery.BOOLEAN_TRUE;
                returnType = ValueType.BOOLEAN;
                qname = FunCall.luxExistsQName;
            }
            else if (funcall.getQName().equals(FunCall.emptyQName)) {
                functionFacts = XPathQuery.BOOLEAN_FALSE;
                returnType = ValueType.BOOLEAN_FALSE;
                qname = FunCall.luxExistsQName;
            }
            if (qname != null) {
                long facts;
                if (query.isImmutable()) {
                    facts = functionFacts | XPathQuery.MINIMAL;
                } else {
                    query.setType(ValueType.INT);
                    query.setFact(functionFacts, true);
                    facts = query.getFacts();
                }
                return new FunCall (qname, returnType, 
                        new LiteralExpression (query.toString()),
                        new LiteralExpression (facts));
            }
        }
        // No optimization, but indicate that this function returns an int?
        // FIXME: this is just wrong? It must have come from some random test case, 
        // but isn't generally true.  If we really need it, move this logic up to the caller - it has nothing
        // to do with optimizing this funcall.
        if (query.isImmutable()) {
            query = XPathQuery.getQuery(query.getQuery(), query.getFacts(), ValueType.INT);
        } else {
            query.setType(ValueType.INT);
        }
        push (query);
        return funcall;
    }
    
    private static final XPathQuery combineQueries(XPathQuery lq, Occur loccur, XPathQuery rq, Occur roccur, ValueType valueType) {
        return lq.combine(loccur, rq, roccur, valueType);
    }
    
    private TermQuery nodeNameTermQuery(Axis axis, QName name) {
        String nodeName = name.getClarkName(); //name.getLocalPart();
        String fieldName = (axis == Axis.Attribute) ? attrQNameField : elementQNameField;
        TermQuery termQuery = new TermQuery (new Term (fieldName, nodeName));
        return termQuery;
    }
    
    @Override
    public AbstractExpression visit(Dot dot) {
        // FIXME - should have value type=VALUE?
        push(XPathQuery.MATCH_ALL);
        return dot;
    }

    @Override
    public AbstractExpression visit(BinaryOperation op) {
        optimizeSubExpressions(op, 0);
        XPathQuery rq = pop();
        XPathQuery lq = pop();
        ValueType type = lq.getResultType().promote (rq.getResultType());
        Occur occur = Occur.SHOULD;
        boolean minimal = false;
        switch (op.getOperator()) {
        
        case AND: 
            occur = Occur.MUST;
        case OR:
            minimal = true;
        case EQUALS: case NE: case LT: case GT: case LE: case GE:
            type = ValueType.BOOLEAN;
            break;
            
        case ADD: case SUB: case DIV: case MUL: case IDIV: case MOD:
            type = ValueType.ATOMIC;
            // Casting an empty sequence to an operand of an operator expeciting atomic values raises an error
            // and to be properly error-preserving we use SHOULD here
            // occur = Occur.MUST
            break;
            
        case AEQ: case ANE: case ALT: case ALE: case AGT: case AGE:
            type = ValueType.BOOLEAN;
            // occur = Occur.MUST;
            break;
            
        case IS: case BEFORE: case AFTER:
            // occur = Occur.MUST;
            break;
            
        case INTERSECT:
            occur = Occur.MUST;
        case UNION:
            minimal = true;
            break;
            
        case EXCEPT:
            push (combineQueries(lq, Occur.MUST, rq, Occur.MUST_NOT, type));
            return op;
        }
        XPathQuery query = combineQueries(lq, occur, rq, occur, type);
        if (minimal == false) {
            query.setFact(XPathQuery.MINIMAL, false);
        }
        push (query);
        return op;
    }

    @Override
    public AbstractExpression visit(LiteralExpression literal) {
        push (XPathQuery.MATCH_NONE);
        return literal;
    }

    @Override
    public AbstractExpression visit(Predicate predicate) {
        // Allow the base expression to be optimized later so we have an opportunity to combine the 
        // predicate query with the base query
        optimizeExpression (predicate.getFilter(), 0, 0);
        XPathQuery filterQuery = pop();
        XPathQuery baseQuery = pop();
        // We can only assert the predicate MUST occur in certain circumstances
        // various function calls blur that - consider not(foo) or count(foo)=0 or count(.//foo) gt count(./foo)
        // TODO: so why are we asserting it universally here?
        XPathQuery query = baseQuery.combine(filterQuery, Occur.MUST);
        // This is a counting expr if its base expr is
        query.setFact(XPathQuery.COUNTING, baseQuery.isFact(XPathQuery.COUNTING));
        push (query);
        return predicate;
    }

    @Override
    public AbstractExpression visit(Subsequence subsequence) {
        optimizeSubExpressions (subsequence, 0);
        AbstractExpression start = subsequence.getStartExpr();
        AbstractExpression length = subsequence.getLengthExpr();
        // TODO: encode pagination information in the call to lux:search created here
        XPathQuery lengthQuery = null;
        if (length != null) {
            lengthQuery = pop ();
        }
        XPathQuery startQuery = pop();
        if (start == FunCall.LastExpression || (start.equals(LiteralExpression.ONE) && length.equals(LiteralExpression.ONE))) {
            // selecting the first or last item from a sequence - this has
            // no effect on the query, its minimality or return type, so
            // just leave the main sub-expression query; don't combine with
            // the start or length queries
            return subsequence;
        }
        XPathQuery baseQuery = pop();
        XPathQuery query = baseQuery.combine(startQuery, Occur.SHOULD);
        if (lengthQuery != null) {
            query = query.combine(lengthQuery, Occur.SHOULD);
        }
        query.setFact(XPathQuery.MINIMAL, false);
        push (query);
        return subsequence;
    }
    
    private FunCall getSearchCall (int i, int facts) {
        int j = queryStack.size() - i - 1;
        XPathQuery query = queryStack.get(j);
        queryStack.set (j, MATCH_ALL_QUERY);
        return createSearchCall(query, facts);
    }
    
    private FunCall createSearchCall(XPathQuery query, int facts) {
        return new FunCall (FunCall.luxSearchQName, query.getResultType(), 
                new LiteralExpression (query.toString()),
                new LiteralExpression (query.getFacts() | facts));
    }

    @Override
    public AbstractExpression visit(Sequence sequence) {
        optimizeSubExpressions(sequence, 0);
        combineTopQueries (sequence.getSubs().length, Occur.SHOULD);
        return sequence;
    }

    private void combineTopQueries (int n, Occur occur) {
        combineTopQueries (n, occur, null);
    }

    private void combineTopQueries (int n, Occur occur, ValueType valueType) {
        if (n <= 0) {
            push (XPathQuery.MATCH_NONE);
            return;
        }
        XPathQuery query = pop();
        for (int i = 0; i < n-1; i++) {
            query = pop().combine(query, occur);
        }
        if (valueType != null && valueType != query.getResultType()) {
            if (query.isImmutable())
                query = XPathQuery.getQuery(query.getQuery(), query.getFacts(), valueType);
            else
                query.setType(valueType);
        }
        push (query);
    }

    public XPathQuery getQuery() {        
        return queryStack.get(queryStack.size()-1);
    }
    
    void push (XPathQuery query) {
        queryStack.add(query);
    }
    
    XPathQuery pop () {
        return queryStack.remove(queryStack.size()-1);
    }

}
