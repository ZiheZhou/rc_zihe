package com.examine.application;

public class VendorNotFoundException extends RuntimeException {

    public VendorNotFoundException(String vendorKey) {
        super("Unknown vendorKey: " + vendorKey);
    }
}
