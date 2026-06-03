# Registry build cache for docker buildx (CI). Set USE_REGISTRY_CACHE=1 to enable
# cache-from; set USE_REGISTRY_CACHE_WRITE=1 only on repos that can push to ghcr.io/gonka-ai/*.
USE_REGISTRY_CACHE ?= 0
USE_REGISTRY_CACHE_WRITE ?= 0

# $(1) = full cache ref, e.g. ghcr.io/gonka-ai/api:buildcache
registry_cache_from = --cache-from type=registry,ref=$(1)
registry_cache_to = --cache-to type=registry,ref=$(1),mode=min
registry_cache_args = $(call registry_cache_from,$(1)) $(if $(filter 1,$(USE_REGISTRY_CACHE_WRITE)),$(call registry_cache_to,$(1)))
