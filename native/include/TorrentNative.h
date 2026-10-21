#ifndef TORRENTTODDL_NATIVE_H
#define TORRENTTODDL_NATIVE_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct ttd_engine ttd_engine;
/* Returned strings and errors are owned by the caller; release with ttd_free. */
ttd_engine *ttd_create(const char *cache_directory, char **error);
void ttd_destroy(ttd_engine *engine);
void ttd_free(void *value);
int ttd_start(ttd_engine *engine, char **error);
void ttd_stop(ttd_engine *engine);
int ttd_load_magnet(ttd_engine *engine, const char *magnet, char **error);
int ttd_load_torrent(ttd_engine *engine, const uint8_t *bytes, size_t size, char **error);
char *ttd_status_json(ttd_engine *engine, char **error);
/* >= 0: bytes read; -1: error; -2: requested piece is not available yet.
   Reads stop at a verified piece boundary; callers must loop. No network data
   is exposed before libtorrent verifies its piece hash. timeout_ms <= 1000.
   expected_id prevents reads crossing a torrent change, even between calls. */
int64_t ttd_read(ttd_engine *engine, const char *expected_id, int32_t file_index,
                 int64_t offset, uint8_t *buffer, size_t capacity,
                 uint32_t timeout_ms, char **error);

#ifdef __cplusplus
}
#endif
#endif
