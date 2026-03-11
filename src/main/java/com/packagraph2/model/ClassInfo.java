package com.packagraph2.model;

public class ClassInfo {

    private String name;
    private String kind;   // class, interface, enum, record, annotation
    private String scope;  // public, protected, private, package-private

    public ClassInfo() {
    }

    public ClassInfo(String name, String kind, String scope) {
        this.name = name;
        this.kind = kind;
        this.scope = scope;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}
