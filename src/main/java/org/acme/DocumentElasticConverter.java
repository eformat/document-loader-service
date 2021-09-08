package org.acme;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.*;

public class DocumentElasticConverter implements Processor {

    private final String fileNameHeader = "CamelFileName";

    @Override
    public void process(Exchange exchange) throws Exception {
        byte[] doc = exchange.getIn().getBody(byte[].class);
        // format is: fileId-=-fileName.ext
        String fullFileName = exchange.getIn().getHeader(fileNameHeader, String.class);
        List<String> list = new ArrayList<String>(Arrays.asList(fullFileName.split("-=-")));
        String fileId = list.get(0);
        list.remove(0);
        String fileName = String.join("", list);
        Map map = new HashMap();
        map.put("data", doc);
        map.put("filename", fileName);
        map.put("url", "https://docs.google.com/document/d/" + fileId + "/edit");
        map.put("fileId", fileId);
        exchange.getIn().setBody(map);
    }

}
