package lux.compiler;

import lux.xml.QName;
import lux.xml.ValueType;
import lux.xpath.AbstractExpression;
import lux.xpath.BinaryOperation;
import lux.xpath.ExpressionVisitorBase;
import lux.xpath.FunCall;
import lux.xpath.NodeTest;
import lux.xpath.PathStep;
import lux.xpath.Root;
import lux.xpath.Sequence;

/**
 * adds up the number of wildcard ("*" or node()) path steps on the left or
 * right-hand side of a path; descendant axis steps count as an infinite
 * number of wildcard steps.
 
 * The path distance underlying this function expresses the "vertical"
 * distance between two sets of nodes.  This is really only defined for
 * expressions separated by some combination of self, child, and
 * descendant steps, and it counts the number of wildcard (*, node())
 * steps.
 */

public class SlopCounter extends ExpressionVisitorBase {
    
    public SlopCounter () {
    }

    private boolean done = false;
    private Integer slop = null;

    /**
     * reset back to the initial state so the counter may be reused.
     */
    public void reset() {
        slop = null;
        done = false;
    }

    @Override
    public AbstractExpression visit(Root root) {
        foundNode();
        return root;
    }

    @Override
    public AbstractExpression visit(PathStep step) {
        NodeTest nodeTest = step.getNodeTest();
        switch (step.getAxis()) {
        case Child:
            if ((nodeTest.getType().equals(ValueType.NODE) || nodeTest.getType().equals(ValueType.ELEMENT))) {
                foundNode();
                if (nodeTest.isWild()) {
                    ++slop;
                } else {
                    done = true;
                }
            } // else? done?
            break;
        case Self:
            if ((nodeTest.getType().equals(ValueType.NODE) || nodeTest.getType().equals(ValueType.ELEMENT))) {
                foundNode();
                if (!isReverse()) {
                    --slop; // self:: matches an adjacent wildcard *on the left* and closes up a gap
                }
                // however - multiple self:: in sequence ??
            } // else? done?
            break;
        case Descendant:
        case DescendantSelf:
            if (isReverse()) {
                // we're going right-to-left
                if (slop == null && !nodeTest.isWild()) {
                    // we see our first thing and it's a named node
                    slop = 0;
                    done = true;
                } else {
                    slop = 98;
                }
            } else {
                // A number bigger than any document would ever be nested?  A
                // document nested this deeply would likely cause other
                // problems.  Surround Query Parser can only parse 2-digit distances
                slop = 98;
            }
            break;
        case Attribute:
            if (nodeTest.getQName() != null) {
                foundNode();
            }
            break;
        default:
            done = true;
            break;
        }
        return step;
    }

    private void foundNode() {
        if (slop == null) {
            slop = 0;
        }
    }

    @Override
    public AbstractExpression visit(FunCall f) {
        QName name = f.getName();
        if (! (name.equals(FunCall.FN_EXISTS) || name.equals(FunCall.FN_DATA) || name.getNamespaceURI().equals(FunCall.XS_NAMESPACE))) {
            // We can infer a path relationship with exists() and data() and constructors because they are 
            // existence-preserving.  We should also be able to invert not(exists()) and 
            // empty(), and not(), etc. in the path index case.
            slop = null;
        }
        done = true;
        return f;
    }

    @Override
    public AbstractExpression visit(Sequence seq) {
    	computeMaxSubSlop(seq);
        done = true;
        return seq;
    }

    @Override
    public AbstractExpression visit(BinaryOperation exp) {
    	computeMaxSubSlop(exp);
        done = true;
        return exp;
    }
    
	private void computeMaxSubSlop(AbstractExpression exp) {
		int maxSlop = -1;
    	Integer origSlop = slop;
    	for (AbstractExpression sub : exp.getSubs()) {
    		sub.accept(this);
    		if (slop == null) {
    		    continue;
    		}
    		done = false; // child step may have indicated we're done ...
    		maxSlop = Math.max(maxSlop, slop);
    		slop = origSlop;
    	}
    	if (maxSlop >= 0) {
    		slop = maxSlop;
    	}
	}

    public Integer getSlop () {
        return slop;
    }
    
    @Override
    public boolean isDone () {
        return done;
    }


}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
