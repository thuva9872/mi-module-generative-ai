/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.esb.module.ai.operations;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.exceptions.ParsingException;
import org.wso2.carbon.esb.module.ai.Constants;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Objects;

/**
 * Parsing operation
 * Inputs:
 * - input: String
 * - parserType: Type of the parser
 * Outputs:
 * - Parsed text
 */
public class DocParser extends AbstractAIMediator {

    enum PARSER {
        TEXT,
        PDF_BOX,
        POI,
        HTML_TO_TEXT
    }

    @Override
    public void execute(MessageContext mc) {
        String input = getMediatorParameter(mc, Constants.INPUT, String.class, false);
        String parserType = getMediatorParameter(mc, Constants.TYPE, String.class, false);

        String responseVariable = getMediatorParameter(mc, Constants.RESPONSE_VARIABLE, String.class, false);
        Boolean overwriteBody = getMediatorParameter(mc, Constants.OVERWRITE_BODY, Boolean.class, false);

        PARSER parser;
        parser = determineParser(parserType);
        if (parser == null) {
            handleConnectorException(Errors.UNSUPPORTED_PARSER_TYPE, mc);
        }

        DocumentParser docParser = null;
        ByteArrayInputStream inputStream = null;
        switch (Objects.requireNonNull(parser)) {
            case TEXT:
                docParser = new TextDocumentParser();
                inputStream = new ByteArrayInputStream(input.getBytes());
                break;
            case HTML_TO_TEXT:
                docParser = new HTMLToTextParser();
                inputStream = new ByteArrayInputStream(input.getBytes());
                break;
            case PDF_BOX:
                docParser = new ApachePdfBoxDocumentParser();
                inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(input));
                break;
            case POI:
                docParser = new ApachePoiDocumentParser();
                inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(input));
                break;
            default:
                handleConnectorException(Errors.UNSUPPORTED_PARSER_INPUT, mc);
        }

        Document doc = null;
        try {
            doc = docParser.parse(inputStream);
        } catch (ParsingException e) {
            handleConnectorException(e.getError(), mc, e);
        } catch (Exception e) {
            handleConnectorException(Errors.PARSE_ERROR, mc, e);
        }

        if (doc == null) {
            handleConnectorException(Errors.PARSE_ERROR, mc);
        }

        handleConnectorResponse(mc, Objects.requireNonNull(doc).text(), null, null,
                responseVariable, overwriteBody);
    }

    private PARSER determineParser(String contentType) {
        return switch (contentType.toLowerCase()) {
            case Constants.MD_TO_TEXT -> PARSER.TEXT;
            case Constants.HTML_TO_TEXT-> PARSER.HTML_TO_TEXT;
            case Constants.PDF_TO_TEXT -> PARSER.PDF_BOX;
            case Constants.DOC_TO_TEXT, Constants.DOCX_TO_TEXT, Constants.PPT_TO_TEXT,
                 Constants.PPTX_TO_TEXT, Constants.XLS_TO_TEXT, Constants.XLSX_TO_TEXT -> PARSER.POI;
            default -> null;
        };
    }
}
