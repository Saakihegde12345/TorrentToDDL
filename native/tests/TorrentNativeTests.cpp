#include "TorrentNative.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

static void check(bool result, std::string const& message) {
    if (!result) throw std::runtime_error(message);
}
static std::vector<uint8_t> readFile(std::filesystem::path const& path) {
    std::ifstream input(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}
int main() {
    auto root = std::filesystem::temp_directory_path() /
        ("torrenttoddl-native-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    std::filesystem::create_directories(root);
    char *error = nullptr;
    auto *engine = ttd_create(root.string().c_str(), &error);
    try {
        check(engine != nullptr, error ? error : "Cannot create engine");
        auto *initial = ttd_status_json(engine, &error);
        check(initial && std::string(initial).find("\"running\":false") != std::string::npos,
              "Construction unexpectedly started networking");
        ttd_free(initial);
        auto payload = readFile(std::filesystem::path(TTD_FIXTURES) / "payload.bin");
        auto metadata = readFile(std::filesystem::path(TTD_FIXTURES) / "payload.torrent");
        std::filesystem::copy_file(std::filesystem::path(TTD_FIXTURES) / "payload.bin", root / "payload.bin");
        check(ttd_start(engine, &error), error ? error : "Start failed");
        check(ttd_load_torrent(engine, metadata.data(), metadata.size(), &error), error ? error : "Load failed");
        std::string status;
        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(15);
        do {
            auto *json = ttd_status_json(engine, &error);
            check(json != nullptr, error ? error : "Status failed");
            status = json;
            ttd_free(json);
            if (status.find("\"cachedBytes\":" + std::to_string(payload.size())) != std::string::npos) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        } while (std::chrono::steady_clock::now() < deadline);
        check(status.find("\"ready\":true") != std::string::npos, "Metadata was not ready");
        auto pos = status.find("\"id\":\"");
        check(pos != std::string::npos, "Missing torrent identity");
        auto identity = status.substr(pos + 6, 40);
        std::vector<uint8_t> received(payload.size());
        size_t offset = 0;
        while (offset < received.size()) {
            auto n = ttd_read(engine, identity.c_str(), 0, offset, received.data() + offset,
                              received.size() - offset, 200, &error);
            if (n == -2 && std::chrono::steady_clock::now() < deadline) continue;
            check(n > 0, error ? error : "Cached read stalled");
            offset += static_cast<size_t>(n);
        }
        check(received == payload, "Full native payload differs from the fixture");
        uint8_t range[7];
        check(ttd_read(engine, identity.c_str(), 0, 123, range, 7, 200, &error) == 7,
              error ? error : "Range read failed");
        check(std::equal(range, range + 7, payload.data() + 123), "Range bytes differ");
        check(ttd_read(engine, "stale", 0, 0, range, 7, 0, &error) == -1, "Stale identity accepted");
        ttd_free(error); error = nullptr;
        check(ttd_read(engine, identity.c_str(), 0, payload.size() + 1, range, 7, 0, &error) == -1,
              "Out-of-bounds read accepted");
        ttd_free(error); error = nullptr;
        ttd_stop(engine);
        check(ttd_read(engine, identity.c_str(), 0, 0, range, 7, 0, &error) == -1,
              "Read succeeded after stop");
        ttd_free(error); error = nullptr;
        auto *stopped = ttd_status_json(engine, &error);
        check(stopped && std::string(stopped).find("\"running\":false") != std::string::npos,
              "Stop left networking active");
        ttd_free(stopped);
        check(ttd_start(engine, &error), "Restart failed");
        check(!ttd_load_torrent(engine, nullptr, 0, &error), "Empty metadata accepted");
        ttd_free(error); error = nullptr;
        ttd_destroy(engine);
        std::filesystem::remove_all(root);
        std::cout << "Native full/range reads, identity, bounds, startup and stop passed\n";
        return 0;
    } catch (std::exception const& failure) {
        std::cerr << failure.what() << '\n';
        ttd_free(error);
        ttd_destroy(engine);
        std::filesystem::remove_all(root);
        return 1;
    }
}
