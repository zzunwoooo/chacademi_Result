package dev.portablevfx.client.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeBloomConfigTest {
    @TempDir Path directory;
    @Test void createsDefaultAndPreservesAuthoredIntensityRatios() throws Exception {
        Path file=directory.resolve("portablevfx/bloom.properties");
        ClaudeBloomConfig.load(file);
        assertTrue(Files.readString(file).contains("strength=0.4"));assertTrue(Files.readString(file).contains("version=2"));
        assertEquals(.4,ClaudeBloomConfig.strength());
        assertEquals(.8,ClaudeBloomConfig.intensity(2),1e-12);
        assertEquals(3,ClaudeBloomConfig.intensity(6)/ClaudeBloomConfig.intensity(2),1e-12);
        assertEquals(0,ClaudeBloomConfig.intensity(0));
    }
    @Test void acceptsOffOriginalAndIntermediateSettings() throws Exception {
        Path file=directory.resolve("bloom.properties");
        try {
            for(double value:new double[]{0,.4,.65,1}) {
                Files.writeString(file,"version=2\nstrength="+value);
                ClaudeBloomConfig.load(file);
                assertEquals(value*2,ClaudeBloomConfig.intensity(2),1e-12);
            }
        } finally { Files.writeString(file,"version=2\nstrength=0.4");ClaudeBloomConfig.load(file); }
    }
    @Test void invalidSettingsRetainSafeDefault() throws Exception {
        Path file=directory.resolve("bloom.properties");
        for(String value:new String[]{"NaN","Infinity","-Infinity","invalid"}) {
            Files.writeString(file,"strength="+value);
            assertThrows(java.io.IOException.class,()->ClaudeBloomConfig.load(file));
            assertEquals(.4,ClaudeBloomConfig.strength());
        }
        Files.writeString(file,"# no value\n");ClaudeBloomConfig.load(file);
        assertEquals(.4,ClaudeBloomConfig.strength());
    }
    @Test void malformedPropertiesStillReportIoAndRetainSafeDefault() throws Exception {
        Path file=directory.resolve("bloom.properties");
        Files.writeString(file,"strength=" + "\\" + "uZZZZ\n");
        assertThrows(java.io.IOException.class,()->ClaudeBloomConfig.load(file));
        assertEquals(.4,ClaudeBloomConfig.strength());
    }
    @Test void finiteOutOfRangeStrengthClampsInsteadOfFallingBack() throws Exception {
        Path file=directory.resolve("bloom.properties");
        try {
            Files.writeString(file,"strength=1.2");ClaudeBloomConfig.load(file);assertEquals(1,ClaudeBloomConfig.strength());
            Files.writeString(file,"strength=-0.1");ClaudeBloomConfig.load(file);assertEquals(0,ClaudeBloomConfig.strength());
            assertEquals(0,ClaudeBloomConfig.intensity(2));
        } finally { Files.writeString(file,"version=2\nstrength=0.4");ClaudeBloomConfig.load(file); }
    }
    @Test void unversionedOldDefaultMigratesOnceButChosenValuesStay() throws Exception {
        Path file=directory.resolve("bloom.properties");
        Files.writeString(file,"# old\nstrength=0.65\n");ClaudeBloomConfig.load(file);
        assertEquals(.4,ClaudeBloomConfig.strength());assertTrue(Files.readString(file).contains("version=2"));
        ClaudeBloomConfig.load(file);assertEquals(.4,ClaudeBloomConfig.strength());
        Files.writeString(file,"strength=0.8\n");ClaudeBloomConfig.load(file);assertEquals(.8,ClaudeBloomConfig.strength());assertTrue(Files.readString(file).contains("version=2"));
        Files.writeString(file,"version=2\nstrength=0.65\n");ClaudeBloomConfig.load(file);assertEquals(.65,ClaudeBloomConfig.strength());
        ClaudeBloomConfig.save(.25);assertEquals(.25,ClaudeBloomConfig.strength());ClaudeBloomConfig.load(file);assertEquals(.25,ClaudeBloomConfig.strength());
        ClaudeBloomConfig.save(3);assertEquals(1,ClaudeBloomConfig.strength());
    }
}
