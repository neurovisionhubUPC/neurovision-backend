package com.neurovision.rehab.clinical.infrastructure.persistence.json;

import static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TherapeuticConfigurationJsonCodecTest {
    private TherapeuticConfigurationJsonCodec codec;

    @BeforeEach
    void setUp() {
        codec = new TherapeuticConfigurationJsonCodec(new ObjectMapper());
    }

    @Test
    void producesCanonicalJsonAndRoundTripsEachParameterType() {
        var scanning = new VisualScanning(VisualScanningMode.SYSTEMATIC_EXPLORATION,
                AffectedHemifield.LEFT, 500, 4000, 15, 50, 10, 1000, 2);
        var search = new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                AffectedHemifield.RIGHT, 10, 4, 500, 5000, new BigDecimal("0.75"), 50, 3);
        var fixation = new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                TargetPosition.RANDOM, 500, 1000, 50, 10, 800, 2);
        assertEquals(scanning, codec.decode(TherapeuticExercise.VISUAL_SCANNING, 1, codec.encode(scanning)));
        assertEquals(search, codec.decode(TherapeuticExercise.VISUAL_SEARCH, 1, codec.encode(search)));
        assertEquals(fixation, codec.decode(TherapeuticExercise.FIXATION, 1, codec.encode(fixation)));
        assertEquals("{\"mode\":\"TARGET_SEARCH_WITH_DISTRACTORS\",\"affectedHemifield\":\"RIGHT\","
                        + "\"targetCount\":10,\"distractorCount\":4,\"minimumFixationMs\":500,"
                        + "\"maximumSearchTimeMs\":5000,\"affectedHemifieldBias\":0.75,"
                        + "\"targetSizePx\":50,\"difficultyLevel\":3}", codec.encode(search));
    }

    @Test
    void rejectsMissingUnknownWrongTypedAndInvalidCrossFieldValues() {
        String valid = "{\"mode\":\"SUSTAINED_TARGET_FIXATION\",\"targetPosition\":\"CENTER\","
                + "\"minimumFixationMs\":500,\"maximumFixationMs\":1000,\"targetSizePx\":50,"
                + "\"repetitions\":10,\"restBetweenRepetitionsMs\":800,\"difficultyLevel\":2}";
        assertThrows(IllegalArgumentException.class, () -> decode(valid.replace(",\"difficultyLevel\":2", "")));
        assertThrows(IllegalArgumentException.class, () -> decode(valid.replace("}", ",\"unknown\":1}")));
        assertThrows(IllegalArgumentException.class, () -> decode(valid.replace("\"minimumFixationMs\":500", "\"minimumFixationMs\":\"500\"")));
        assertThrows(IllegalArgumentException.class, () -> decode(valid.replace("\"maximumFixationMs\":1000", "\"maximumFixationMs\":499")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.FIXATION, 2, valid));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.VISUAL_SEARCH, 1, valid));
    }

    @Test
    void rejectsMalformedAndNonFiniteNumbers() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.FIXATION, 1, "{"));
        String search = "{\"mode\":\"TARGET_SEARCH_WITH_DISTRACTORS\",\"affectedHemifield\":\"LEFT\","
                + "\"targetCount\":5,\"distractorCount\":0,\"minimumFixationMs\":300,"
                + "\"maximumSearchTimeMs\":3000,\"affectedHemifieldBias\":NaN,"
                + "\"targetSizePx\":30,\"difficultyLevel\":1}";
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(TherapeuticExercise.VISUAL_SEARCH, 1, search));
    }

    @Test
    void rejectsNullCoercibleAndUnknownFieldsAcrossTheCatalog() {
        String scanning = codec.encode(new VisualScanning(VisualScanningMode.STANDARD_SCANNING,
                AffectedHemifield.LEFT, 300, 2000, 5, 30, 5, 500, 1));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.VISUAL_SCANNING, 1,
                scanning.replace("\"affectedHemifield\":\"LEFT\"", "\"affectedHemifield\":null")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.VISUAL_SCANNING, 1,
                scanning.replace("}", ",\"unexpected\":true}")));

        String search = codec.encode(new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                AffectedHemifield.RIGHT, 5, 0, 300, 3000, new BigDecimal("0.5"), 30, 1));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.VISUAL_SEARCH, 1,
                search.replace("\"targetCount\":5", "\"targetCount\":\"5\"")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.VISUAL_SEARCH, 1,
                search.replace("\"affectedHemifieldBias\":0.5", "\"affectedHemifieldBias\":null")));

        String fixation = codec.encode(new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                TargetPosition.CENTER, 300, 500, 30, 5, 500, 1));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(TherapeuticExercise.FIXATION, 1,
                fixation.replace("\"targetPosition\":\"CENTER\"", "\"targetPosition\":null")));
    }

    private TherapeuticExerciseParameters decode(String value) {
        return codec.decode(TherapeuticExercise.FIXATION, 1, value);
    }
}
