/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epimorphics.registry.csv;

import java.io.InputStream;

import com.epimorphics.registry.core.Status;
import com.epimorphics.registry.util.Prefixes;
import com.epimorphics.registry.vocab.RegistryVocab;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.vocabulary.RDF;

import static com.epimorphics.registry.csv.RDFCSVUtil.*;

/**
 * Utility to read a CSV upload stream as either a set of RDF entities 
 * or as a set of RegisterItems with associated entities.
 * Non-streaming in-memory implementation. 
 */
public class CSVPayloadRead {

    public static Model readCSVStream(InputStream ins, String baseURI) {
        Model payload = ModelFactory.createDefaultModel();
        CSVRDFReader reader = new CSVRDFReader(ins, Prefixes.get());
        reader.setBaseURI(baseURI);
        boolean withItems = reader.hasColumn(NOTATION_HEADER);
        
        Resource entity = null;
        while ((entity = reader.nextResource(payload)) != null) {
            if (withItems) {
                String notation = reader.getColumnValue(NOTATION_HEADER);
                Resource item = payload.createResource(baseURI + "_" + notation)
                        .addProperty(RegistryVocab.notation, notation)
                        .addProperty(RDF.type, RegistryVocab.RegisterItem);
                if (reader.hasColumn(STATUS_HEADER)) {
                    String status = reader.getColumnValue(STATUS_HEADER);
                    item.addProperty(RegistryVocab.status, Status.forString(status, Status.Submitted).getResource());
                }
                Resource description = payload.createResource()
                        .addProperty(RegistryVocab.entity, entity);
                item.addProperty(RegistryVocab.definition, description);
            }
        }
        return payload;
    }
}