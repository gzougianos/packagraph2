package com.packagraph2.model;

public class ImportDetail {

    private String sourceClass;
    private String importedClass;

    public ImportDetail() {
    }

    public ImportDetail(String sourceClass, String importedClass) {
        this.sourceClass = sourceClass;
        this.importedClass = importedClass;
    }

    public String getSourceClass() {
        return sourceClass;
    }

    public void setSourceClass(String sourceClass) {
        this.sourceClass = sourceClass;
    }

    public String getImportedClass() {
        return importedClass;
    }

    public void setImportedClass(String importedClass) {
        this.importedClass = importedClass;
    }
}
