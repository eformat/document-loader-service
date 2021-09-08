package org.acme;

public class FileExistsException extends Exception {

    public FileExistsException(String message) {
        super(message);
    }
}
