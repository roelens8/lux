package lux.solr;

import java.util.ArrayList;

import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;

import lux.CachingDocReader;
import lux.LuxURIResolver;
import lux.exception.LuxException;
import lux.exception.NotFoundException;
import lux.index.FieldName;
import lux.index.IndexConfiguration;
import lux.index.field.IDField;
import lux.search.LuxSearcher;
import net.sf.saxon.s9api.XdmNode;

import org.apache.lucene.search.Sort;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SortSpec;

/**
 * Retrieves documents from distributed Solr (SolrCloud); called from fn:doc()
 * 
 * FIXME: the URIResolver gets set on the Saxon Configuration object, which is shared across
 * multiple threads.  All of XQueryComponent, SolrIndexConfig, and Processor are per-core resources,
 * and can be shared across threads.  There's no need to constantly reallocate and reset the URIResolver.
 * We can just make one and leave it there.
 * 
 * But we do need access to some query-scoped data in the resolver: namely the document builder, since
 * it tracks allocated document numbers.  Well perhaps we can allocate a new builder since the numbers
 * are persistent?
 */
public class SolrURIResolver extends LuxURIResolver {

    private final XQueryComponent xqueryComponent;
    private final String xmlFieldName;
    private final String idFieldName;
    
    SolrURIResolver(XQueryComponent xqueryComponent, URIResolver systemURIResolver) {
        super (systemURIResolver, null,  
                xqueryComponent.getSolrIndexConfig().getIndexConfig().getFieldName(FieldName.URI));
        this.xqueryComponent = xqueryComponent;
        IndexConfiguration indexConfig = xqueryComponent.getSolrIndexConfig().getIndexConfig();
        this.xmlFieldName = indexConfig.getFieldName(FieldName.XML_STORE);
        this.idFieldName = indexConfig.getFieldName(IDField.getInstance());
    }

    @Override
    public XdmNode getDocument(String uri) throws TransformerException {

        String[] shards = xqueryComponent.getCurrentShards();
        if (shards != null) {
            return getDocumentDistrib (uri);
        } else {
            return super.getDocument (uri);
        }
    }

    private XdmNode getDocumentDistrib(String uri) throws NotFoundException {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.add((CommonParams.Q), uriFieldName + ":\"" + uri.replaceAll("([\\\\\\\"])", "\\$1") + '"');
        params.add(CommonParams.FL, uriFieldName, xmlFieldName, idFieldName);
        params.add(CommonParams.WT, "javabin");
        params.add(CommonParams.VERSION, "2");
        params.add("distrib", "true");
        String[] shards = xqueryComponent.getCurrentShards();
        StringBuilder shardBuffer = new StringBuilder(shards[0]);
        for (int i = 0; i < shards.length; i++) {
            shardBuffer.append(',').append(shards[i]);
        }
        params.add("shards", shardBuffer.toString());
        CloudQueryRequest req = new CloudQueryRequest(xqueryComponent.getCore(), params, new SortSpec(Sort.RELEVANCE, 1));
        // don't need to query for docids?
        req.setNextStage(ResponseBuilder.STAGE_GET_FIELDS);
        SolrQueryResponse response = new SolrQueryResponse();
        // TODO: we could probably figure out which shard has the document and only query that one
        // instead of broadcasting this request to all of them
        xqueryComponent.getSearchHandler().handleRequest(req, response);
        if (response.getException() != null) {
            throw new LuxException ("An error occurred while retrieving " + uri, response.getException());
        }
        SolrDocumentList docs = (SolrDocumentList) response.getValues().get("response");
        if (docs.isEmpty()) {
            throw new NotFoundException ("document '" + uri + "' not found");
        }
        if (docs.size() > 1) {
            throw new NotFoundException ("found " + docs.size() + " documents with uri='" + uri + "'");
        }
        SolrDocument doc = docs.get(0);
        Long docID = (Long) doc.get(idFieldName);
        Object xml = doc.get(xmlFieldName);
        if (xml instanceof ArrayList<?>) {
            // why does this come back as an ArrayList?
            xml = ((ArrayList<?>)xml).get(0);
        }
        String xmlString = null;
        byte[] xmlBytes = null;
        if (xml instanceof String) {
            xmlString = (String) xml;
        } else {
            // Must be a tinybin if it's not a String
            xmlBytes = (byte[]) xml;
        }
        XdmNode node = getDocReader().createXdmNode(docID, uri, xmlString, xmlBytes);
        doc.removeFields(xmlFieldName);
        node.getUnderlyingNode().getDocumentRoot().setUserData(SolrDocument.class.getName(), doc);
        return node;
    }
    
    @Override
    public LuxSearcher getSearcher() {
        return xqueryComponent.getEvaluator().getSearcher();
    }

    @Override
    public CachingDocReader getDocReader() {
        return xqueryComponent.getEvaluator().getDocReader();
    }

}
