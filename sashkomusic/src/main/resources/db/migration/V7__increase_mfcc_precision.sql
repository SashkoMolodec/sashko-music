-- Increase precision for MFCC variance fields which can exceed DECIMAL(10,6)
-- MFCC variances can be quite large, especially for lower coefficients

ALTER TABLE tracks_analyzed
    ALTER COLUMN mfcc_1_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_1_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_2_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_2_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_3_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_3_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_4_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_4_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_5_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_5_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_6_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_6_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_7_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_7_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_8_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_8_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_9_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_9_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_10_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_10_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_11_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_11_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_12_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_12_var TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_13_mean TYPE DECIMAL(14,6),
    ALTER COLUMN mfcc_13_var TYPE DECIMAL(14,6);

-- Comment
COMMENT ON COLUMN tracks_analyzed.mfcc_1_var IS 'Increased to DECIMAL(14,6) to accommodate large variance values';
