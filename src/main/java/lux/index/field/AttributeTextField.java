package lux.index.field;

import java.util.Collections;

import lux.index.IndexConfiguration;
import lux.index.XmlIndexer;
import lux.index.analysis.AttributeTokenStream;
import lux.index.analysis.DefaultAnalyzer;
import lux.index.analysis.QNameTokenFilter;
import lux.xml.SaxonDocBuilder;
import net.sf.saxon.s9api.XdmNode;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.Field.TermVector;
import org.apache.lucene.document.Fieldable;

/**
 * Indexes the text in each attribute of a document
 */
public class AttributeTextField extends FieldDefinition {
    
    private static final AttributeTextField instance = new AttributeTextField();
        
    public static AttributeTextField getInstance() {
        return instance;
    }
    
    protected AttributeTextField () {
        super ("lux_att_text", new DefaultAnalyzer(), Store.NO, Type.TOKENS, TermVector.NO);
    }
    
    @Override
    public Iterable<Fieldable> getFieldValues(XmlIndexer indexer) {
        XdmNode doc = indexer.getXdmNode();
        if (doc != null && doc.getUnderlyingNode() != null) {
            SaxonDocBuilder builder = indexer.getSaxonDocBuilder();
            String fieldName = indexer.getConfiguration().getFieldName(this);
            AttributeTokenStream tokens = new AttributeTokenStream(fieldName, getAnalyzer(), doc, builder.getOffsets());
            ((QNameTokenFilter) tokens.getWrappedTokenStream()).setNamespaceAware(indexer.getConfiguration().isOption(IndexConfiguration.NAMESPACE_AWARE));
            return new FieldValues (indexer.getConfiguration(), this, Collections.singleton(
                        new Field(indexer.getConfiguration().getFieldName(this), tokens, getTermVector())));
        }
        return Collections.emptySet();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
