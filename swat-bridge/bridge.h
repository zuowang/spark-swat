#ifndef BRIDGE_H
#define BRIDGE_H

#include <assert.h>
#include <string.h>
#include <stdio.h>
#include <map>
#include <set>
#include <string>
#include <pthread.h>

#include "device_context.h"
#include "common.h"
#include "kernel_arg.h"
#include "allocator.h"

using namespace std;

#ifdef __APPLE__
#include <OpenCL/opencl.h>
#else
#include <CL/cl.h>
#endif

#define JNI_JAVA(type, className, methodName) JNIEXPORT type JNICALL Java_org_apache_spark_rdd_cl_##className##_##methodName

class mem_and_size {
    public:
        mem_and_size(cl_region *set_mem, size_t set_size) : mem(set_mem),
            size(set_size), valid(true) { }
        mem_and_size() : valid(false) { }

        cl_region *get_mem() { assert(valid); return mem; }
        size_t get_size() { assert(valid); return size; }
        bool is_valid() { return valid; }
    private:
        cl_region *mem;
        size_t size;
        bool valid;
};

class rdd_partition_offset {
    public:
        rdd_partition_offset(int set_rdd, int set_index, int set_offset, int set_component) :
            rdd(set_rdd), index(set_index), offset(set_offset), component(set_component) { }

        bool operator<(const rdd_partition_offset& other) const {
            if (rdd < other.rdd) {
                return true;
            } else if (rdd > other.rdd) {
                return false;
            }

            if (index < other.index) {
                return true;
            } else if (index > other.index) {
                return false;
            }

            if (offset < other.offset) {
                return true;
            } else if (offset > other.offset) {
                return false;
            }

            return component < other.component;
        }

        int get_partition() { return index; }

    private:
        // The RDD this buffer is a member of
        int rdd;
        // The partition in rdd
        int index;
        // The offset in elements inside the partition
        int offset;
        /*
         * The component of this buffer we are storing (e.g. multiple buffers
         * are necessary to represent Tuple2 RDDs
         */
        int component;
};

enum arg_type {
    REGION,
    INT,
    FLOAT,
    DOUBLE
};

typedef union _region_or_scalar {
    cl_region *region;
    int i;
    float f;
    double d;
} region_or_scalar;

typedef struct _arg_value {
    int index;
    bool keep; // only set for region type
    bool dont_free; // only set for region type
    bool clear_arguments; // only set for region type
    size_t len; // only set for region type
    enum arg_type type;
    region_or_scalar val;
} arg_value;

typedef struct _native_input_buffer_list_node {
    int id;
    cl_event event;
    struct _native_input_buffer_list_node *next;
} native_input_buffer_list_node;

typedef struct _kernel_context kernel_context;
typedef struct _native_output_buffers native_output_buffers;

typedef struct _swat_context {
    cl_kernel kernel;
    pthread_mutex_t kernel_lock;

    int host_thread_index;

    arg_value *accumulated_arguments;
    int accumulated_arguments_len;
    int accumulated_arguments_capacity;

    arg_value *global_arguments;
    int global_arguments_len;
    int global_arguments_capacity;

    void *zeros;
    size_t zeros_capacity;

    cl_event last_write_event;

    native_input_buffer_list_node *freed_native_input_buffers;
    pthread_mutex_t freed_native_input_buffers_lock;
    pthread_cond_t freed_native_input_buffers_cond;

    unsigned run_seq_no;

    kernel_context *completed_kernels;
    pthread_mutex_t completed_kernels_lock;
    pthread_cond_t completed_kernels_cond;

    native_output_buffers *out_buffers;
    int out_buffers_len;
    pthread_mutex_t out_buffers_lock;
    pthread_cond_t out_buffers_cond;

#ifdef BRIDGE_DEBUG
    map<int, kernel_arg *> *debug_arguments;
    char *kernel_src;
    size_t kernel_src_len;
#endif

} swat_context;

/*
 * The host-side storage of a single heap instance transferred from the device.
 */
typedef struct _saved_heap {
    void *h_heap;
    size_t size;
} saved_heap;

typedef struct _native_output_buffers {
    void **buffers;
    size_t *buffer_sizes;
    int *buffer_arg_indices;
    int n_buffers;
    int free;
} native_output_buffers;

struct _kernel_context {
    swat_context *ctx;
    device_context *dev_ctx;

    heap_context *curr_heap_ctx;
    saved_heap *heaps;
    cl_event *heap_copy_back_events;
    int n_heap_ctxs;
    int heapStartArgnum;

    size_t n_loaded;
    size_t local_size;
    size_t global_size;

    unsigned seq_no;

    unsigned iter;
    int iterArgNum;

    kernel_context *next;

    // The set of arguments that are specific to this kernel instance
    arg_value *accumulated_arguments;
    int accumulated_arguments_len;

    native_output_buffers *out_buffers;
};


#endif
