package com.examine.application;

public class VendorConfigNotFoundException extends RuntimeException {

    public VendorConfigNotFoundException(String vendorKey) {
        super("VendorConfig not found: " + vendorKey);
    }
}
