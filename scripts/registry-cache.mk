# BuildKit registry cache refs (USE_REGISTRY_CACHE=1).
# In CI set REGISTRY_CACHE_OWNER to github.repository_owner (e.g. a-kuprin on forks, gonka-ai upstream).
REGISTRY_CACHE_OWNER ?= gonka-ai
REGISTRY_CACHE_PREFIX = ghcr.io/$(REGISTRY_CACHE_OWNER)

# $(1) = GHCR package name (api, inferenced, mock-server, ...)
registry_cache_flags = --cache-from type=registry,ref=$(REGISTRY_CACHE_PREFIX)/$(1):buildcache --cache-to type=registry,ref=$(REGISTRY_CACHE_PREFIX)/$(1):buildcache,mode=min
registry_cache_flags_upgrade = --cache-from type=registry,ref=$(REGISTRY_CACHE_PREFIX)/$(1):buildcache-upgrade --cache-to type=registry,ref=$(REGISTRY_CACHE_PREFIX)/$(1):buildcache-upgrade,mode=min
