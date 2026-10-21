#include "TorrentNative.h"
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_flags.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <thread>

namespace lt = libtorrent;
namespace fs = std::filesystem;

struct ttd_engine {
    std::mutex mutex;
    fs::path root;
    std::unique_ptr<lt::session> session;
    lt::torrent_handle handle;
    uint64_t generation = 0;
    bool cachedPaused = false;
    std::string lastError;
};

namespace {
char *copyString(std::string const& text) {
    auto *result = static_cast<char *>(std::malloc(text.size() + 1));
    if (!result) throw std::bad_alloc();
    std::memcpy(result, text.c_str(), text.size() + 1);
    return result;
}
void errorOut(char **out, char const *message) noexcept {
    if (out) {
        try { *out = copyString(message); } catch (...) { *out = nullptr; }
    }
}
std::string quoted(std::string const& text) {
    std::ostringstream out;
    out << '"';
    constexpr char hex[] = "0123456789abcdef";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c < 32) out << "\\u00" << hex[c >> 4] << hex[c & 15];
        else out << c;
    }
    out << '"';
    return out.str();
}
std::string identity(lt::torrent_handle const& handle) {
    auto bytes = handle.info_hashes().get_best().to_string();
    constexpr char hex[] = "0123456789abcdef";
    std::string result;
    for (unsigned char c : bytes) { result += hex[c >> 4]; result += hex[c & 15]; }
    return result;
}
void requireEngine(ttd_engine *engine) {
    if (!engine) throw std::runtime_error("Torrent engine is unavailable");
}
fs::path safePath(fs::path const& root, std::string const& relative) {
    auto candidate = fs::weakly_canonical(root / fs::path(relative));
    auto r = root.begin(), p = candidate.begin();
    for (; r != root.end(); ++r, ++p) {
        if (p == candidate.end() || *r != *p) {
            throw std::runtime_error("Torrent path escapes the cache directory");
        }
    }
    if (p == candidate.end()) throw std::runtime_error("Invalid torrent file path");
    return candidate;
}
void validateFiles(ttd_engine const& engine, lt::torrent_info const& info) {
    auto const& files = info.files();
    if (files.num_files() > 20000) throw std::runtime_error("Torrent contains too many files");
    for (int index = 0; index < files.num_files(); ++index) {
        auto file = lt::file_index_t(index);
        if (files.file_flags(file) & lt::file_storage::flag_symlink) {
            throw std::runtime_error("Symlink torrents are not supported in this preview");
        }
        safePath(engine.root, files.file_path(file));
    }
}
void addTorrent(ttd_engine& engine, lt::add_torrent_params parameters) {
    if (!engine.session) throw std::runtime_error("Start the gateway first");
    if (parameters.ti) validateFiles(engine, *parameters.ti);
    parameters.save_path = engine.root.string();
    parameters.flags &= ~lt::torrent_flags::paused;
    parameters.flags &= ~lt::torrent_flags::auto_managed;
    parameters.flags |= lt::torrent_flags::sequential_download;
    if (engine.handle.is_valid()) engine.session->remove_torrent(engine.handle);
    engine.handle = {};
    ++engine.generation;
    engine.lastError.clear();
    engine.cachedPaused = false;
    lt::settings_pack settings;
    settings.set_bool(lt::settings_pack::enable_dht, true);
    engine.session->apply_settings(settings);
    engine.session->resume();
    lt::error_code ec;
    engine.handle = engine.session->add_torrent(std::move(parameters), ec);
    if (ec) throw std::runtime_error(ec.message());
}
}

extern "C" {
void ttd_free(void *value) { std::free(value); }

ttd_engine *ttd_create(char const *cache_directory, char **error) {
    if (error) *error = nullptr;
    try {
        if (!cache_directory || !*cache_directory) throw std::runtime_error("Missing cache directory");
        auto engine = std::make_unique<ttd_engine>();
        fs::create_directories(cache_directory);
        engine->root = fs::canonical(cache_directory);
        return engine.release();
    } catch (std::exception const& e) { errorOut(error, e.what()); }
      catch (...) { errorOut(error, "Unable to create torrent engine"); }
    return nullptr;
}

int ttd_start(ttd_engine *engine, char **error) {
    if (error) *error = nullptr;
    try {
        requireEngine(engine);
        std::lock_guard<std::mutex> lock(engine->mutex);
        if (engine->session) return 1;
        lt::settings_pack settings;
        settings.set_str(lt::settings_pack::user_agent, "TorrentToDDL/iOS-preview");
        settings.set_str(lt::settings_pack::listen_interfaces, "0.0.0.0:0,[::]:0");
        settings.set_bool(lt::settings_pack::enable_dht, true);
        // No multicast entitlement or automatic router port mappings required.
        settings.set_bool(lt::settings_pack::enable_lsd, false);
        settings.set_bool(lt::settings_pack::enable_upnp, false);
        settings.set_bool(lt::settings_pack::enable_natpmp, false);
        settings.set_int(lt::settings_pack::max_metadata_size, 16 * 1024 * 1024);
        settings.set_int(lt::settings_pack::alert_mask,
            static_cast<int>(static_cast<uint32_t>(lt::alert_category::error | lt::alert_category::status | lt::alert_category::storage)));
        engine->session = std::make_unique<lt::session>(settings);
        engine->lastError.clear();
        return 1;
    } catch (std::exception const& e) { errorOut(error, e.what()); }
      catch (...) { errorOut(error, "Unable to start torrent networking"); }
    return 0;
}

void ttd_stop(ttd_engine *engine) {
    if (!engine) return;
    std::unique_ptr<lt::session> old;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        ++engine->generation;
        engine->handle = {};
        old = std::move(engine->session);
        engine->cachedPaused = false;
        engine->lastError.clear();
    }
    // Session destruction joins native threads, outside the state lock/UI thread.
    old.reset();
}

void ttd_destroy(ttd_engine *engine) { ttd_stop(engine); delete engine; }

int ttd_load_magnet(ttd_engine *engine, char const *magnet, char **error) {
    if (error) *error = nullptr;
    try {
        requireEngine(engine);
        if (!magnet || std::strlen(magnet) > 65536) throw std::runtime_error("Invalid magnet link");
        lt::error_code ec;
        auto parameters = lt::parse_magnet_uri(magnet, ec);
        if (ec) throw std::runtime_error(ec.message());
        std::lock_guard<std::mutex> lock(engine->mutex);
        addTorrent(*engine, std::move(parameters));
        return 1;
    } catch (std::exception const& e) { errorOut(error, e.what()); }
      catch (...) { errorOut(error, "Unable to load magnet"); }
    return 0;
}

int ttd_load_torrent(ttd_engine *engine, uint8_t const *bytes, size_t size, char **error) {
    if (error) *error = nullptr;
    try {
        requireEngine(engine);
        if (!bytes || size == 0 || size > 16 * 1024 * 1024) {
            throw std::runtime_error("Torrent metadata must be between 1 byte and 16 MiB");
        }
        lt::error_code ec;
        lt::add_torrent_params parameters;
        parameters.ti = std::make_shared<lt::torrent_info>(
            lt::span<char const>(reinterpret_cast<char const *>(bytes), size), ec, lt::from_span);
        if (ec) throw std::runtime_error(ec.message());
        std::lock_guard<std::mutex> lock(engine->mutex);
        addTorrent(*engine, std::move(parameters));
        return 1;
    } catch (std::exception const& e) { errorOut(error, e.what()); }
      catch (...) { errorOut(error, "Invalid torrent metadata"); }
    return 0;
}

char *ttd_status_json(ttd_engine *engine, char **error) {
    if (error) *error = nullptr;
    try {
        requireEngine(engine);
        std::lock_guard<std::mutex> lock(engine->mutex);
        std::vector<lt::alert *> alerts;
        if (engine->session) {
            engine->session->pop_alerts(&alerts);
            for (auto *alert : alerts) {
                if (auto *failure = lt::alert_cast<lt::torrent_error_alert>(alert)) {
                    engine->lastError = failure->error.message();
                }
            }
        }
        auto info = engine->handle.is_valid() ? engine->handle.torrent_file() : nullptr;
        bool ready = info != nullptr;
        if (info) validateFiles(*engine, *info);
        lt::torrent_status status;
        if (engine->handle.is_valid()) status = engine->handle.status();
        if (status.errc) engine->lastError = status.errc.message();
        if (ready && status.is_seeding && !engine->cachedPaused && engine->session) {
            engine->session->pause();
            lt::settings_pack settings;
            settings.set_bool(lt::settings_pack::enable_dht, false);
            engine->session->apply_settings(settings);
            engine->cachedPaused = true;
        }
        auto state = !engine->lastError.empty() ? "Error" : !engine->session ? "Off"
            : !engine->handle.is_valid() ? "Ready for a torrent" : !ready ? "Fetching metadata"
            : status.is_seeding ? "Cached · peer transfers paused" : "Receiving torrent data";
        std::ostringstream out;
        out << "{\"running\":" << (engine->session ? "true" : "false")
            << ",\"ready\":" << (ready ? "true" : "false")
            << ",\"state\":" << quoted(state)
            << ",\"error\":" << quoted(engine->lastError)
            << ",\"id\":" << quoted(ready ? identity(engine->handle) : "")
            << ",\"name\":" << quoted(info ? info->name() : "")
            << ",\"cachedBytes\":" << std::max<int64_t>(0, status.total_wanted_done)
            << ",\"totalBytes\":" << std::max<int64_t>(0, status.total_wanted)
            << ",\"rate\":" << std::max(0, status.download_payload_rate)
            << ",\"peers\":" << std::max(0, status.num_peers) << ",\"files\":[";
        if (info) {
            auto const& files = info->files();
            for (int index = 0; index < files.num_files(); ++index) {
                auto file = lt::file_index_t(index);
                if (index) out << ',';
                out << "{\"index\":" << index << ",\"name\":" << quoted(files.file_name(file).to_string())
                    << ",\"size\":" << files.file_size(file) << '}';
            }
        }
        out << "]}";
        return copyString(out.str());
    } catch (std::exception const& e) { errorOut(error, e.what()); }
      catch (...) { errorOut(error, "Unable to read torrent status"); }
    return nullptr;
}

int64_t ttd_read(ttd_engine *engine, char const *expected_id, int32_t file_index,
    int64_t offset, uint8_t *buffer, size_t capacity, uint32_t timeout_ms, char **error) {
    if (error) *error = nullptr;
    try {
        requireEngine(engine);
        if (!buffer || capacity == 0 || capacity > 1024 * 1024 || offset < 0 || !expected_id) {
            throw std::runtime_error("Invalid file read parameters");
        }
        lt::torrent_handle handle;
        std::shared_ptr<lt::torrent_info const> info;
        uint64_t generation;
        fs::path path;
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            handle = engine->handle;
            if (!handle.is_valid() || identity(handle) != expected_id) {
                throw std::runtime_error("Torrent changed or gateway stopped");
            }
            info = handle.torrent_file();
            if (!info || file_index < 0 || file_index >= info->files().num_files()) {
                throw std::runtime_error("Torrent file is unavailable");
            }
            path = safePath(engine->root, info->files().file_path(lt::file_index_t(file_index)));
            generation = engine->generation;
        }
        auto const file = lt::file_index_t(file_index);
        auto const size = info->files().file_size(file);
        if (offset > size) throw std::runtime_error("Read offset exceeds file size");
        if (offset == size) return 0;
        auto const global = info->files().file_offset(file) + offset;
        auto const piece = lt::piece_index_t(global / info->piece_length());
        auto const deadline = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(std::min<uint32_t>(1000, timeout_ms));
        if (!handle.have_piece(piece)) handle.set_piece_deadline(piece, 0);
        for (;;) {
            {
                std::lock_guard<std::mutex> lock(engine->mutex);
                if (generation != engine->generation) throw std::runtime_error("Gateway stopped or torrent changed");
            }
            if (!handle.is_valid()) throw std::runtime_error("Torrent is no longer active");
            if (handle.have_piece(piece)) break;
            if (std::chrono::steady_clock::now() >= deadline) return -2;
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
        }
        auto const available = std::min<int64_t>({
            static_cast<int64_t>(capacity), size - offset,
            info->piece_length() - global % info->piece_length()});
        std::ifstream input(path, std::ios::binary);
        input.seekg(offset);
        input.read(reinterpret_cast<char *>(buffer), available);
        if (input.gcount() != available) throw std::runtime_error("Cached torrent data could not be read");
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            if (generation != engine->generation) throw std::runtime_error("Torrent changed during read");
        }
        return available;
    } catch (std::exception const& e) { errorOut(error, e.what()); }
      catch (...) { errorOut(error, "Torrent file read failed"); }
    return -1;
}
}
