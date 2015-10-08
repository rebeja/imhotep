#include "executor_service.hpp"

#include "imhotep_error.hpp"
#include "log.hpp"

namespace imhotep {

    ExecutorService::ExecutorService(size_t threads)
        : _num_tasks_running(threads)
        , _stop(false) {
        for (size_t i = 0; i < threads; ++i) {
            _workers.emplace_back(std::bind(&ExecutorService::work, this));
        }
    }

    void ExecutorService::work() {
        const Unblocked unblocked(*this);
        while (true)  {
            std::function<void()> task;
            {
                std::unique_lock<std::mutex> lock(_mutex);

                _num_tasks_running --;
                if (_num_tasks_running == 0 && _tasks.empty()) {
                    _completion_condition.notify_all();
                }

                _condition.wait(lock, unblocked);

                if (_stop) {
                    return;
                }

                task = std::move(_tasks.front());
                _tasks.pop();

                _num_tasks_running ++;
            }

            try {
                task();
            }
            catch(std::exception const& ex) {
                {
                    std::unique_lock<std::mutex> lock(_mutex);

                    _stop = true;
                    if (!_failure_cause.empty()) {
                        _failure_cause = ex.what();
                    }
                }
                _completion_condition.notify_all();
            }
        }
    }

    void ExecutorService::await_completion(void) {
        const Complete               complete(*this);
        std::unique_lock<std::mutex> lock(_mutex);
        _completion_condition.wait(lock, complete);

        if (!_failure_cause.empty()) throw imhotep_error(_failure_cause);
    }

    ExecutorService::~ExecutorService() {
        {
            std::unique_lock<std::mutex> lock(_mutex);
            _stop = true;
        }
        _condition.notify_all();

        for (std::vector<std::thread>::iterator it(_workers.begin());
             it != _workers.end(); ++it) {
            it->join();
        }

        _completion_condition.notify_all();
    }

    size_t ExecutorService::num_processors() {
        static constexpr size_t fallback(8);
        const size_t            result(std::thread::hardware_concurrency());

        return result != 0 ? result : fallback;
    }

} // namespace imhotep
