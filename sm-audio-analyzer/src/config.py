import os

class Config:
    # Paths
    ANALYSIS_RESULTS_PATH = os.getenv('ANALYSIS_RESULTS_PATH', '/Users/okravch/my/sm/analysis-results')

    # Analysis settings
    ANALYSIS_VERSION = os.getenv('ANALYSIS_VERSION', '1.0')

    # HTTP server
    HTTP_PORT = int(os.getenv('HTTP_PORT', '8090'))

    # Callback URL (Java monolith)
    CALLBACK_URL = os.getenv('CALLBACK_URL', 'http://localhost:8080')
