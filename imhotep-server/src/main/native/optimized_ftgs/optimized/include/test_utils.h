#ifndef TEST_UTILS_H
#define TEST_UTILS_H

#ifndef restrict
#define restrict __restrict__
#endif

extern "C" {
#include "test_patch.h"
}

#include <algorithm>
#include <array>
#include <cassert>
#include <iostream>
#include <iterator>
#include <vector>

typedef int     DocId;
typedef int32_t GroupId;
typedef int64_t Metric;


template <size_t n_metrics>
struct Metrics : public std::array<Metric, n_metrics>
{
  Metrics() { std::fill(this->begin(), this->end(), 0); }
};

template <size_t n_metrics>
std::ostream& operator<<(std::ostream& os, const Metrics<n_metrics>& row) {
  for (auto element: row) os << element << " ";
  return os;
}


template <size_t n_metrics>
struct ShardAttrs {
    typedef Metrics<n_metrics> metrics_t;

    int32_t* sizes           = 0;
    int32_t* vec_nums        = 0;
    int32_t* offsets_in_vecs = 0;

    ShardAttrs(const metrics_t& mins, const metrics_t& maxes)
        : sizes(get_sizes(n_metrics, mins.data(), maxes.data()))
        , vec_nums(get_vec_nums(n_metrics, mins.data(), maxes.data(), sizes))
        , offsets_in_vecs(get_offsets_in_vecs(n_metrics, mins.data(), maxes.data(), sizes))
    { }

    ~ShardAttrs() {
        free(sizes);
        free(vec_nums);
        free(offsets_in_vecs);
    }

    ShardAttrs(const ShardAttrs& rhs) = delete;
};


template <typename int_t = int, typename iterator, typename buffer_t>
void varint_encode(iterator begin, iterator end, buffer_t& out)
{
  std::back_insert_iterator<buffer_t> result(std::back_inserter(out));

  iterator current(begin);
  while (current != end) {
    int_t value(*current);

    if (value < 0) {
      *result++ = ((value&0x7F) | 0x80);
      *result++ =(((value>>7)&0x7F) | 0x80);
      *result++ =(((value>>14)&0x7F) | 0x80);
      *result++ =(((value>>21)&0x7F) | 0x80);
      *result++ =(((value>>28)&0x7F) | 0x80);
    }
    else if (value < 1 << 7) {
      *result++ = value;
    }
    else if (value < 1 << 14) {
      *result++ =((value&0x7F) | 0x80);
      *result++ =(value>>7);
    }
    else if (value < 1 << 21) {
      *result++ =((value&0x7F) | 0x80);
      *result++ =(((value>>7)&0x7F) | 0x80);
      *result++ =(value>>14);
    }
    else if (value < 1 << 28) {
      *result++ =((value&0x7F) | 0x80);
      *result++ =(((value>>7)&0x7F) | 0x80);
      *result++ =(((value>>14)&0x7F) | 0x80);
      *result++ =(value>>21);
    }
    else {
      *result++ =((value&0x7F) | 0x80);
      *result++ =(((value>>7)&0x7F) | 0x80);
      *result++ =(((value>>14)&0x7F) | 0x80);
      *result++ =(((value>>21)&0x7F) | 0x80);
      *result++ =(value>>28);
    }
    ++current;
  }
}

template <typename iterator, typename buffer_t>
void doc_ids_encode(iterator begin, iterator end, buffer_t& out)
{
  std::vector<uint32_t> deltas;
  uint32_t              value(0);

  for (iterator current(begin); current != end; ++current) {
    deltas.push_back(*current - value);
    value = *current;
  }

  varint_encode(deltas.begin(), deltas.end(), out);
}

class VarIntView
{
protected:
    const char* _begin  = 0;
    const char* _end    = 0;

public:
    VarIntView(const char* begin=0, const char* end=0)
        : _begin(begin) , _end(end)
    { }

    const char* begin() const { return _begin; }
    const char* end()   const { return _end;   }

    bool empty() const { return _begin >= _end; }

    uint8_t read() {
        assert(!empty());
        const char result(empty() ? -1 : *_begin);
        ++_begin;
        return result;
    }

    template <typename int_t>
    int_t read_varint(uint8_t b=0) {
        int_t result(0);
        int   shift(0);
        do {
            result |= ((b & 0x7FL) << shift);
            if (b < 0x80) return result;
            shift += 7;
            b = read();
        } while (true);
    }
};

template <class T, size_t N>
std::ostream& operator<<(std::ostream& os, const std::array<T, N>& items)
{
  for (typename std::array<T, N>::const_iterator it(items.begin()); it != items.end(); ++it) {
    if (it != items.begin()) os << " ";
    os << *it;
  }
  return os;
}

template <class T>
std::ostream& operator<<(std::ostream& os, const std::vector<T>& items)
{
  for (typename std::vector<T>::const_iterator it(items.begin()); it != items.end(); ++it) {
    if (it != items.begin()) os << " ";
    os << *it;
  }
  return os;
}

template <>
std::ostream& operator<<<uint8_t>(std::ostream& os, const std::vector<uint8_t>& items)
{
	static char digits[16] = { '0', '1', '2', '3', '4', '5', '6', '7',
														 '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

  for (typename std::vector<uint8_t>::const_iterator it(items.begin()); it != items.end(); ++it) {
    if (it != items.begin()) os << " ";
    std::array<char, 3> hex = { { digits[*it >> 4], digits[*it & 0x0f], '\0' } };
    os << hex.data();
  }
  return os;
}

#endif
