package me.cortex.vulkanite.lib.other;

public class VulkanException extends RuntimeException {
    private final int errorCode;
    
    public VulkanException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
    
    public VulkanException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    public int getErrorCode() {
        return errorCode;
    }
}