import essentia
import essentia.standard as es
import numpy as np
from pathlib import Path
import logging
from typing import Dict, Optional

logger = logging.getLogger(__name__)


class EssentiaAnalyzer:
    """Audio analysis using Essentia library"""

    def __init__(self):
        self.version = "1.0"

    def analyze_track(self, audio_path: str) -> Dict:
        """
        Extract comprehensive audio features from a track.

        Returns dict with all features or raises exception on error.
        """
        logger.info(f"Analyzing track: {audio_path}")

        if not Path(audio_path).exists():
            raise FileNotFoundError(f"Audio file not found: {audio_path}")

        try:
            # Load audio
            loader_mono = es.MonoLoader(filename=audio_path, sampleRate=44100)
            audio_mono = loader_mono()

            logger.debug(f"Audio loaded: {len(audio_mono)} samples")

            # Extract all features
            features = {}

            # 1. Rhythmic Features
            rhythmic = self._extract_rhythmic_features(audio_mono)
            features['rhythmic'] = rhythmic

            # 2. Timbral Features (MFCC)
            timbral = self._extract_timbral_features(audio_mono)
            features['timbral'] = timbral

            # 3. Energy Features
            energy = self._extract_energy_features(audio_path, audio_mono)
            features['energy'] = energy

            logger.info(f"Analysis complete: BPM={rhythmic.get('bpm', 'N/A')}, "
                       f"Danceability={rhythmic.get('danceability', 'N/A')}")

            return features

        except Exception as e:
            logger.error(f"Failed to analyze {audio_path}: {e}", exc_info=True)
            raise

    def _extract_rhythmic_features(self, audio: np.ndarray) -> Dict:
        """Extract BPM, danceability, beats, onset rate"""
        features = {}

        # BPM and beats
        rhythm_extractor = es.RhythmExtractor2013(method="multifeature")
        bpm, beats, beats_confidence, _, beats_intervals = rhythm_extractor(audio)

        features['bpm'] = self._to_string(bpm, 2)

        # Danceability
        try:
            danceability_result = es.Danceability()(audio)
            # Danceability може повернути tuple або float
            danceability = danceability_result if not isinstance(danceability_result, tuple) else danceability_result[0]
            features['danceability'] = self._to_string(danceability, 4)
        except Exception as e:
            logger.warning(f"Danceability extraction failed: {e}")
            features['danceability'] = None

        # Beats loudness (average RMS of beat positions)
        if len(beats) > 0:
            beats_loudness = self._compute_beats_loudness(audio, beats)
            features['beats_loudness'] = self._to_string(beats_loudness, 4)
        else:
            features['beats_loudness'] = None

        # Onset rate
        try:
            onset_detection = es.OnsetRate()
            onset_result = onset_detection(audio)

            # --- FIX START: OnsetRate завжди повертає (onsetRate, onsetTimes) ---
            if isinstance(onset_result, tuple) or isinstance(onset_result, list):
                onset_rate = onset_result[0]
            else:
                onset_rate = onset_result

            # Переконуємось що це скаляр, а не масив
            if isinstance(onset_rate, np.ndarray):
                onset_rate = float(onset_rate.item()) if onset_rate.size == 1 else float(np.mean(onset_rate))
            # --- FIX END ---

            features['onset_rate'] = self._to_string(onset_rate, 4)
        except Exception as e:
            logger.warning(f"Onset rate extraction failed: {e}")
            features['onset_rate'] = None

        return features

    def _extract_timbral_features(self, audio: np.ndarray) -> Dict:
        """Extract MFCC, spectral centroid, rolloff, dissonance"""
        features = {}

        # Compute spectral features frame-by-frame
        w = es.Windowing(type='hann')
        spectrum = es.Spectrum()
        mfcc_extractor = es.MFCC(numberCoefficients=13)
        centroid_extractor = es.Centroid()
        rolloff_extractor = es.RollOff()

        # --- FIX START: Додано SpectralPeaks ---
        # Dissonance потребує частот і амплітуд піків, а не просто спектру
        peak_extractor = es.SpectralPeaks(orderBy="magnitude",
                                        magnitudeThreshold=0.00001,
                                        minFrequency=20,
                                        maxFrequency=20000,
                                        maxPeaks=100)
        dissonance_extractor = es.Dissonance()
        # --- FIX END ---

        frame_size = 2048
        hop_size = 512

        mfcc_frames = []
        centroids = []
        rolloffs = []
        dissonances = []

        # Process audio in frames
        for i in range(0, len(audio) - frame_size, hop_size):
            frame = audio[i:i + frame_size]
            if len(frame) < frame_size:
                break

            spec = spectrum(w(frame))

            # MFCC
            try:
                mfcc_bands, mfcc_coeffs = mfcc_extractor(spec)
                mfcc_frames.append(mfcc_coeffs)
            except:
                pass

            # Spectral centroid
            try:
                centroids.append(centroid_extractor(spec))
            except:
                pass

            # Spectral rolloff
            try:
                rolloffs.append(rolloff_extractor(spec))
            except:
                pass

            # --- FIX START: Рахуємо Dissonance правильно ---
            try:
                # Беремо піки зі спектру
                frequencies, magnitudes = peak_extractor(spec)
                # Рахуємо дисонанс на основі піків
                dis = dissonance_extractor(frequencies, magnitudes)
                dissonances.append(dis)
            except:
                pass
            # --- FIX END ---

        # MFCC statistics
        if mfcc_frames:
            mfcc_array = np.array(mfcc_frames)
            for i in range(13):
                coeff_num = i + 1
                features[f'mfcc_{coeff_num}_mean'] = self._to_string(np.mean(mfcc_array[:, i]), 6)
                features[f'mfcc_{coeff_num}_var'] = self._to_string(np.var(mfcc_array[:, i]), 6)
        else:
            for i in range(13):
                coeff_num = i + 1
                features[f'mfcc_{coeff_num}_mean'] = None
                features[f'mfcc_{coeff_num}_var'] = None

        # Spectral features
        features['spectral_centroid'] = self._to_string(np.mean(centroids), 2) if centroids else None
        features['spectral_rolloff'] = self._to_string(np.mean(rolloffs), 2) if rolloffs else None

        # --- FIX: Додаємо дисонанс у результат ---
        features['dissonance'] = self._to_string(np.mean(dissonances), 6) if dissonances else None

        return features

    def _extract_energy_features(self, audio_path: str, audio_mono: np.ndarray) -> Dict:
        """Extract loudness (EBU R128) and dynamic complexity"""
        features = {}

        # Loudness (LUFS) - need stereo audio
        try:
            loader_stereo = es.AudioLoader(filename=audio_path)
            audio_stereo, sample_rate, channels, _, _, _ = loader_stereo()

            loudness_extractor = es.LoudnessEBUR128()
            loudness_values = loudness_extractor(audio_stereo)
            integrated_loudness = loudness_values[2] if isinstance(loudness_values, tuple) else loudness_values
            features['loudness'] = self._to_string(integrated_loudness, 2)
        except Exception as e:
            logger.warning(f"Loudness extraction failed: {e}")
            features['loudness'] = None

        # Dynamic complexity
        try:
            dynamic_complexity_result = es.DynamicComplexity()(audio_mono)
            dynamic_complexity = dynamic_complexity_result if not isinstance(dynamic_complexity_result, tuple) else dynamic_complexity_result[0]
            features['dynamic_complexity'] = self._to_string(dynamic_complexity, 6)
        except Exception as e:
            logger.warning(f"Dynamic complexity extraction failed: {e}")
            features['dynamic_complexity'] = None

        return features

    def _compute_beats_loudness(self, audio: np.ndarray, beats: np.ndarray) -> float:
        """Compute average RMS loudness at beat positions"""
        sample_rate = 44100
        window_samples = 2048

        rms_values = []
        for beat_time in beats:
            sample_pos = int(beat_time * sample_rate)
            if sample_pos + window_samples < len(audio):
                window = audio[sample_pos:sample_pos + window_samples]
                rms = np.sqrt(np.mean(window ** 2))
                rms_values.append(rms)

        return float(np.mean(rms_values)) if rms_values else 0.0

    def _to_string(self, value: float, decimals: int) -> Optional[str]:
        """Convert float to string with specified precision"""
        if value is None or np.isnan(value) or np.isinf(value):
            return None
        return f"{value:.{decimals}f}"
