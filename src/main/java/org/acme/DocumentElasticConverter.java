package org.acme;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.*;

public class DocumentElasticConverter implements Processor {

    private final String fileNameHeader = "CamelFileName";

    @Override
    public void process(Exchange exchange) throws Exception {
        byte[] doc = exchange.getIn().getBody(byte[].class);
        // format is: project-=-fileId-=-uuid-=-fileName.ext
        String fullFileName = exchange.getIn().getHeader(fileNameHeader, String.class);
        List<String> list = new ArrayList<String>(Arrays.asList(fullFileName.split("-=-")));
        String project = list.get(0);
        String uuid = list.get(1);
        String fileId = list.get(2);
        list.subList(0,3).clear();
        String fileName = String.join("", list);
        Map map = new HashMap();
        map.put("data", doc);
        map.put("filename", fileName);
        map.put("url", "https://docs.google.com/document/d/" + fileId + "/edit");
        map.put("fileId", fileId);
        map.put("uuid", uuid);
        map.put("project", project);
        exchange.getIn().setBody(map);
    }

}
