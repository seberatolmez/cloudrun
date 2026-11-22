package dev.cloudrun.job;

/** Request rejected by validation inside JobService (byte-size limits, runtime whitelist). */
public class InvalidJobException extends RuntimeException {

    public InvalidJobException(String message) {
        super(message);
    }
}
