package com.neurovision.rehab.analytics.domain.model.valueobjects;

public enum AnalyticsCalculationProfile {
    BASELINE_V1("baseline-v1", 10, 10, 10);

    private final String calculationVersion;
    private final int gridRows;
    private final int gridColumns;
    private final int confidenceBinCount;

    AnalyticsCalculationProfile(String calculationVersion, int gridRows, int gridColumns,
            int confidenceBinCount) {
        this.calculationVersion = calculationVersion;
        this.gridRows = gridRows;
        this.gridColumns = gridColumns;
        this.confidenceBinCount = confidenceBinCount;
    }

    public String calculationVersion() {
        return calculationVersion;
    }

    public int gridRows() {
        return gridRows;
    }

    public int gridColumns() {
        return gridColumns;
    }

    public int confidenceBinCount() {
        return confidenceBinCount;
    }

    public boolean filtersByConfidence() {
        return false;
    }

    public boolean weightsHeatmapByConfidence() {
        return false;
    }
}
