/*
 * RESTHeart - the data REST API server
 * Copyright (C) 2014 - 2015 SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.handlers.applicationlogic;

import org.bson.types.ObjectId;
import org.restheart.handlers.PipedHttpHandler;
import org.restheart.handlers.RequestContext;
import org.restheart.utils.HttpStatus;
import org.restheart.utils.RequestHelper;
import org.restheart.utils.ResponseHelper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class FileHandler extends ApplicationLogicHandler {

    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static final String CONTENT_TRANSFER_ENCODING_BINARY = "binary";
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FileHandler.class);
	
    @SuppressWarnings("unused")
	private final String msg;

    /**
     *
     * @param next
     * @param args
     */
    public FileHandler(PipedHttpHandler next, Map<String, Object> args) {
        super(next, args);

        this.msg = (String) args.get("msg");
    }

    /**
     *
     * @param exchange
     * @param context
     * @throws Exception
     */
    @Override
    public void handleRequest(HttpServerExchange exchange, RequestContext context) throws Exception {
        LOGGER.debug("GET " + exchange.getRequestURL());
        final String[] bucket = extractDbAndBucketName(context.getUnmappedRequestUri());
        String url = exchange.getRequestURL();
        String fileName = url.substring( url.lastIndexOf('/')+1, url.length() );
        
        GridFS gridfs = new GridFS(getDatabase().getDB(bucket[0]), bucket[1]);
        GridFSDBFile dbsfile = gridfs.findOne(new BasicDBObject("filename", fileName));

        if (dbsfile == null) {
            fileNotFound(context, exchange);
        } else {
            if (!checkEtag(exchange, dbsfile)) {
                sendBinaryContent(dbsfile, exchange);
            }
        }
    }
    
    private boolean checkEtag(HttpServerExchange exchange, GridFSDBFile dbsfile) {
        if (dbsfile != null) {
            Object etag = dbsfile.get("_etag");

            if (etag != null && etag instanceof ObjectId) {
                // add the _lastupdated_on in case the _etag field is present and its value is an ObjectId
                dbsfile.put("_lastupdated_on", Instant.ofEpochSecond(((ObjectId)etag).getTimestamp()).toString());

                // in case the request contains the IF_NONE_MATCH header with the current etag value,
                // just return 304 NOT_MODIFIED code
                if (RequestHelper.checkReadEtag(exchange, (ObjectId)etag)) {
                    ResponseHelper.endExchange(exchange, HttpStatus.SC_NOT_MODIFIED);
                    return true;
                }
            }
        }

        return false;
    }

    private void fileNotFound(RequestContext context, HttpServerExchange exchange) {
        final String errMsg = String.format("File with ID <%s> not found", context.getDocumentId());
        LOGGER.error(errMsg);
        ResponseHelper.endExchangeWithMessage(exchange, HttpStatus.SC_NOT_FOUND, errMsg);
    }

    private void sendBinaryContent(final GridFSDBFile dbsfile, final HttpServerExchange exchange) throws IOException {
        LOGGER.debug("Filename = {}", dbsfile.getFilename());
        LOGGER.debug("Content length = {}", dbsfile.getLength());

        if (dbsfile.get("contentType") != null) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, dbsfile.get("contentType").toString());
        } else {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_OCTET_STREAM);
        }
        
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, dbsfile.getLength());
        exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION,
                String.format("inline; filename=\"%s\"", extractFilename(dbsfile)));
        exchange.getResponseHeaders().put(Headers.CONTENT_TRANSFER_ENCODING, CONTENT_TRANSFER_ENCODING_BINARY);
        ResponseHelper.injectEtagHeader(exchange, dbsfile);
        
        exchange.setResponseCode(HttpStatus.SC_OK);

        dbsfile.writeTo(exchange.getOutputStream());
        exchange.endExchange();
    }

    private String extractFilename(final GridFSDBFile dbsfile) {
        return dbsfile.getFilename() != null ? dbsfile.getFilename() : dbsfile.getId().toString();
    }

    static String[] extractDbAndBucketName(final String collectionName) {
    	
    	String[] pathParts = collectionName.split("/");
    	for (int i = 0; i < pathParts.length; i++) {
    		String[] file = pathParts[i].split("\\.");
    		if(file.length > 1){
    			if(file[1].equals("files")){
    				 String[] bucket = new String[2];
					bucket [0] = pathParts[i - 1];
    				bucket [1] = file[0];
    				return bucket;
    			}
    		}
			
		}
    	
    	
        return new String[2];
    }

/*    GetFileBinaryHandler(Object object, Object object0) {
        super(null, null);
    }*/
    
}
