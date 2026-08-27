package com.toadzip.backend.announcement.domain;

public enum SupplyCategory {
    NEW_SUPPLY,
    RESUPPLY;

    public SupplyType toSupplyType() {
        return switch (this) {
            case NEW_SUPPLY -> SupplyType.NEW;
            case RESUPPLY -> SupplyType.RESUPPLY;
        };
    }
}
