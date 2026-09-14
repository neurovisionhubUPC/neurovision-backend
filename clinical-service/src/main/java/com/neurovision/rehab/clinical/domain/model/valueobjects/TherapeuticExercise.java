package com.neurovision.rehab.clinical.domain.model.valueobjects;

public enum TherapeuticExercise {
    VISUAL_SCANNING("visual-scanning"),
    VISUAL_SEARCH("visual-search"),
    FIXATION("fixation");

    private final ExerciseCode code;

    TherapeuticExercise(String code) {
        this.code = new ExerciseCode(code);
    }

    public ExerciseCode code() {
        return code;
    }

    public static TherapeuticExercise from(ExerciseCode code) {
        for (var exercise : values()) {
            if (exercise.code.equals(code)) return exercise;
        }
        throw new IllegalArgumentException("Unsupported therapeutic exercise: " + code.value());
    }
}
