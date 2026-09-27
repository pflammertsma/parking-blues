"""Production WSGI entrypoint. Flask's own dev server (used by
`python -m backend.app`) explicitly warns against production use; this is
what gunicorn/Cloud Run's buildpack actually run. See README section 10.
"""

from backend.app import create_app

app = create_app()
