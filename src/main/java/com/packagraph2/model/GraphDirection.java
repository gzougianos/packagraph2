package com.packagraph2.model;

public enum GraphDirection {
    TOP_TO_BOTTOM("TB"),
    BOTTOM_TO_TOP("BT"),
    LEFT_TO_RIGHT("LR"),
    RIGHT_TO_LEFT("RL");

    private final String dotValue;

    GraphDirection(String dotValue) {
        this.dotValue = dotValue;
    }

    public String getDotValue() {
        return dotValue;
    }
}
