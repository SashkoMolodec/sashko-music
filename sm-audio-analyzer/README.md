# SM Audio Analyzer

Python microservice for audio analysis using Essentia library.

## Features

Extracts comprehensive audio features for music recommendation and analysis:

### Rhythmic Features
- **BPM**: Tempo detection using Essentia RhythmExtractor2013
- **Danceability**: Dance-ability score (0-1)
- **Beats Loudness**: Average loudness at beat positions
- **Onset Rate**: Number of onsets per second

### Timbral Features
- **MFCC**: 13 Mel-frequency cepstral coefficients (mean + variance = 26 values)
- **Spectral Centroid**: Center of mass of the spectrum
- **Spectral Rolloff**: Frequency below which 85% of energy is contained
- **Dissonance**: Harmonic dissonance measure

### Energy Features
- **Loudness**: EBU R128 integrated loudness (LUFS)
- **Dynamic Complexity**: Dynamic range complexity

## Architecture

- **Input**: Kafka messages from `analyze-track-tasks` topic
- **Processing**: Essentia audio analysis
- **Output**: JSON files with results + Kafka completion messages to `track-analysis-complete` topic

## Dependencies

- Python 3.12
- Essentia 2.1b6
- Kafka Python client
- NumPy

## Configuration

Environment variables:
- `KAFKA_BOOTSTRAP_SERVERS` (default: redpanda:9091)
- `KAFKA_CONSUMER_GROUP` (default: audio-analyzer-group)
- `ANALYSIS_RESULTS_PATH` (default: /analysis-results)
- `ANALYSIS_VERSION` (default: 1.0)

## Docker

```bash
docker build -t sm-audio-analyzer .
docker run -v /path/to/music:/music:ro -v /path/to/results:/analysis-results sm-audio-analyzer
```

## Development

```bash
pip install -r requirements.txt
python src/main.py
```

## License

MIT
