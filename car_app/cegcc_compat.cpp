// cegcc / mingw32ce compatibility shims.
//
// With -static-libgcc, libgcc's gthr-win32 support references the TLS-key
// cleanup helpers __mingwthr_key_dtor / __mingwthr_remove_key_dtor, which
// normally live in libmingwthrd. We do not link that library (the image must
// depend on coredll alone, which tools/pe_check.py enforces), and we use no
// __thread destructors, so no-op definitions satisfy the linker with no
// behavioural change. extern "C" keeps the names unmangled so libgcc's C
// references resolve.

extern "C" int __mingwthr_key_dtor(unsigned long key, void (*dtor)(void *))
{
    (void)key;
    (void)dtor;
    return 0;
}

extern "C" int __mingwthr_remove_key_dtor(unsigned long key)
{
    (void)key;
    return 0;
}
