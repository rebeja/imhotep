#ifndef MERGE_ITERATOR_HPP
#define MERGE_ITERATOR_HPP

#include <algorithm>
#include <utility>
#include <vector>

#include <boost/iterator/iterator_facade.hpp>

#include "shard.hpp"
#include "split_iterator.hpp"

namespace imhotep {

    template <typename term_t>
    class MergeIterator
        : public boost::iterator_facade<MergeIterator<term_t>,
                                        std::pair<term_t, Shard::packed_table_ptr> const,
                                        boost::forward_traversal_tag> {
    public:
        typedef std::pair<SplitIterator<term_t>, Shard::packed_table_ptr> Entry;

        MergeIterator() { }

        template <typename iterator>
        MergeIterator(iterator begin, iterator end) {
            std::copy_if(begin, end, std::back_inserter(_its),
                         [] (Entry& entry) {
                             static SplitIterator<term_t> split_end;
                             return entry.first != split_end;
                         });
            increment();
        }

    private:
        friend class boost::iterator_core_access;

        /*
        struct CompareIt {
            bool operator()(const Entry& thing1,
                            const Entry& thing2) {
                return *thing1.first > *thing2.first;
            }
        };
        */

        void increment() {
            static const SplitIterator<term_t> end;

            if (_its.empty()) return;

            auto lowest(_its.begin());
            for (auto it(_its.begin()); it != _its.end(); ++it) {
                const Entry& entry(*it);
                if (*entry.first < *lowest->first) {
                    lowest = it;
                }
            }

            Entry& entry(*lowest);
            _current = std::make_pair(*entry.first, entry.second);
            ++entry.first;
            if (entry.first == end) _its.erase(lowest);
        }

        bool equal(const MergeIterator& other) const {
            return _its == other._its;
        }

        typename MergeIterator::reference dereference() const {
            return _current;
        }

        std::vector<Entry> _its;

        typename MergeIterator::value_type _current;
    };
}

#endif
