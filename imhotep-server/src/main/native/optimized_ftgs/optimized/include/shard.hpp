#ifndef SHARD_HPP
#define SHARD_HPP

#include <string>

#define restrict __restrict__
extern "C" {
#include "imhotep_native.h"
}
#include "term.hpp"

namespace imhotep {

    class Shard {
        const std::string     _dir;
        const packed_table_t* _table = 0;
    public:
        const std::string& dir() const { return _dir; }

        const packed_table_t* table() const { return _table; }

        template <typename term_t>
        static std::string term_filename(const std::string& shard_dir,
                                         const std::string& field) {
            return base_filename(shard_dir, field) +
                TermTraits<term_t>::term_file_extension();
        }

        template <typename term_t>
        static std::string docid_filename(const std::string& shard_dir,
                                          const std::string& field) {
            return base_filename(shard_dir, field) +
                TermTraits<term_t>::docid_file_extension();
        }

        static std::string name_of(const std::string& shard) {
            const std::string::size_type pos(shard.find_last_of('/'));
            return pos == std::string::npos ?
                shard : shard.substr(pos + 1);
        }

    private:
        static std::string base_filename(const std::string& shard_dir,
                                         const std::string& field) {
            return shard_dir + "/fld-" + field + ".";
        }
    };

} // namespace imhotep

#endif
