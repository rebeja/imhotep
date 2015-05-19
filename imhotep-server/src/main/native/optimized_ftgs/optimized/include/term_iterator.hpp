#ifndef TERM_ITERATOR_HPP
#define TERM_ITERATOR_HPP

#include <cassert>
#include <cstring>
#include <memory>
#include <string>

#include <boost/iterator/iterator_facade.hpp>

#include "term.hpp"
#include "var_int_view.hpp"

namespace imhotep {

    template <typename term_t>
    struct term_iterator_traits {
        typedef void buffer_t;
    };

    template <typename term_t>
    class term_iterator
        : public boost::iterator_facade<term_iterator<term_t>, term_t const,
                                        boost::forward_traversal_tag> {
    public:
        term_iterator() { }

        term_iterator(const std::string& filename)
            : _view(std::make_shared<MMappedVarIntView>(filename)) {
            increment();
        }

        term_iterator(const char* begin, const char* end)
            : _view(std::make_shared<VarIntView>(begin, end)) {
            increment();
        }

    private:
        friend class boost::iterator_core_access;

        void increment();

        bool equal(const term_iterator& other) const { return *_view == *other._view; }

        const term_t& dereference() const { return _current; }

        std::shared_ptr<VarIntView> _view = std::make_shared<VarIntView>();

        term_t _current;

        typename term_iterator_traits<term_t>::buffer_t _id_buffer;
    };

    template <> struct term_iterator_traits<IntTerm> {
        struct Unused { };
        typedef Unused buffer_t;
    };

    template <> struct term_iterator_traits<StringTerm> {
        typedef std::string buffer_t;
    };

    template<>
    void term_iterator<IntTerm>::increment() {
        if (!_view->empty()) {
            const long id_delta(_view->read_varint<long>(_view->read()));
            const long offset_delta(_view->read_varint<long>(_view->read()));
            const long doc_freq(_view->read_varint<long>(_view->read()));
            _current = IntTerm(_current.id() + id_delta,
                               _current.doc_offset() + offset_delta,
                               doc_freq);
        }
        else {
            _view = std::make_shared<MMappedVarIntView>();
        }
    }

    template<>
    void term_iterator<StringTerm>::increment() {
        if (!_view->empty()) {
            const long erase(_view->read_varint<long>(_view->read()));
            const long append(_view->read_varint<long>(_view->read()));
            _id_buffer.erase(_id_buffer.size() - erase, erase);
            for (size_t count(0); count < size_t(append); ++count) {
                _id_buffer.push_back(_view->read());
            }

            const long offset_delta(_view->read_varint<long>(_view->read()));
            const long doc_freq(_view->read_varint<long>(_view->read()));
            _current = StringTerm(_id_buffer,
                                  _current.doc_offset() + offset_delta,
                                  doc_freq);
        }
        else {
            _view = std::make_shared<MMappedVarIntView>();
        }
    }

    typedef term_iterator<IntTerm>    int_term_iterator;
    typedef term_iterator<StringTerm> string_term_iterator;

} // namespace imhotep

#endif
