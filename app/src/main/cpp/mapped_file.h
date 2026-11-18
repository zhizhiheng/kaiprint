#pragma once

#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdint>
#include <vector>

class MappedFile {
public:
    explicit MappedFile(int fd) {
        struct stat info{};
        if (fstat(fd, &info) != 0 || info.st_size <= 0 || info.st_size > 512 * 1024 * 1024) return;
        size_ = static_cast<size_t>(info.st_size);
        mapping_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapping_ != MAP_FAILED) return;

        copy_.resize(size_);
        size_t offset = 0;
        while (offset < size_) {
            const auto count = pread(fd, copy_.data() + offset, size_ - offset, static_cast<off_t>(offset));
            if (count <= 0) {
                copy_.clear();
                size_ = 0;
                return;
            }
            offset += static_cast<size_t>(count);
        }
    }

    ~MappedFile() { if (mapping_ != MAP_FAILED) munmap(mapping_, size_); }
    MappedFile(const MappedFile&) = delete;
    MappedFile& operator=(const MappedFile&) = delete;

    void* data() { return mapping_ != MAP_FAILED ? mapping_ : (copy_.empty() ? nullptr : copy_.data()); }
    size_t size() const { return size_; }

private:
    void* mapping_ = MAP_FAILED;
    size_t size_ = 0;
    std::vector<uint8_t> copy_;
};
