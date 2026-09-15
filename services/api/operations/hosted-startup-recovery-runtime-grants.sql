-- Apply last after baseline grants. Recovery is deliberately unavailable to the API role.
REVOKE ALL ON hosted_startup_recoveries FROM mural_runtime;
