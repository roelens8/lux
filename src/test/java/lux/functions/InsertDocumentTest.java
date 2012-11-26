package lux.functions;

import static lux.index.IndexConfiguration.*;
import lux.IndexTestSupport;
import lux.index.XmlIndexer;

import org.apache.lucene.store.RAMDirectory;
import org.junit.BeforeClass;
import org.junit.Test;

public class InsertDocumentTest extends XQueryTest {
    
    private static IndexTestSupport indexTestSupport;
    
    @BeforeClass
    public static void setup() throws Exception {
        RAMDirectory dir = new RAMDirectory();
        indexTestSupport = new IndexTestSupport(null, new XmlIndexer(INDEX_PATHS|INDEX_FULLTEXT|STORE_XML), dir);
        evaluator = indexTestSupport.makeEvaluator();
    }
    
    @Test
    public void testInsertDocument () throws Exception {
        assertXQuery(null, "lux:insert('/test.xml', <test>this is a test</test>)");
        // TODO: I don't like that this throws an error, but what else can you do in a URI
        // resolver???
        assertXQuery(null, "doc('/test.xml')", "document '/test.xml' not found");
        assertXQuery(null, "lux:commit()");
        assertXQuery("this is a test", "doc('/test.xml')/test/string()");
        assertXQuery("/test.xml", "lux:search('this is a test')/base-uri()");
        assertXQuery(null, "lux:delete('/test.xml')");
        assertXQuery("true", "doc-available('/test.xml')");
        assertXQuery(null, "lux:commit()");
        assertXQuery("false", "doc-available('/test.xml')");
    }

}