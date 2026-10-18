# SPDX-License-Identifier: Apache-2.0
#
# Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
# Modified by Vaayil: rebuilds when a Go or C source or the Makefile changes; adds the host-side test target for dnsintercept and stackdump; drops the UAPI socket-directory flag; links with GODEBUG madvdontneed=1.

BUILDDIR ?= $(CURDIR)/build
DESTDIR ?= $(CURDIR)/out

NDK_GO_ARCH_MAP_x86 := 386
NDK_GO_ARCH_MAP_x86_64 := amd64
NDK_GO_ARCH_MAP_arm := arm
NDK_GO_ARCH_MAP_arm64 := arm64
NDK_GO_ARCH_MAP_mips := mipsx
NDK_GO_ARCH_MAP_mips64 := mips64x

comma := ,
CLANG_FLAGS := --target=$(TARGET) --sysroot=$(SYSROOT)
export CGO_CFLAGS := $(CLANG_FLAGS) $(subst -mthumb,-marm,$(CFLAGS))
export CGO_LDFLAGS := $(CLANG_FLAGS) $(patsubst -Wl$(comma)--build-id=%,-Wl$(comma)--build-id=none,$(LDFLAGS)) -Wl,-soname=libwg-go.so
export GOARCH := $(NDK_GO_ARCH_MAP_$(ANDROID_ARCH_NAME))
export GOOS := android
export CGO_ENABLED := 1

GO_VERSION := 1.24.3
GO_PLATFORM := $(shell uname -s | tr '[:upper:]' '[:lower:]')-$(NDK_GO_ARCH_MAP_$(shell uname -m))
GO_TARBALL := go$(GO_VERSION).$(GO_PLATFORM).tar.gz
GO_HASH_darwin-amd64 := 13e6fe3fcf65689d77d40e633de1e31c6febbdbcb846eb05fc2434ed2213e92b
GO_HASH_darwin-arm64 := 64a3fa22142f627e78fac3018ce3d4aeace68b743eff0afda8aae0411df5e4fb
GO_HASH_linux-amd64 := 3333f6ea53afa971e9078895eaa4ac7204a8c6b5c68c10e6bc9a33e8e391bdd8

default: $(DESTDIR)/libwg-go.so

# Everything go build compiles into libwg-go.so, so an edited source rebuilds the library.
GO_SOURCES := $(wildcard *.go *.c) $(filter-out %_test.go,$(wildcard dnsintercept/*.go stackdump/*.go))

$(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL):
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	curl -o "$@.tmp" "https://dl.google.com/go/$(GO_TARBALL)" && \
	echo "$(GO_HASH_$(GO_PLATFORM))  $@.tmp" | sha256sum -c && \
	mv "$@.tmp" "$@"'

$(BUILDDIR)/go-$(GO_VERSION)/.prepared: $(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL)
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	tar -C "$(dir $@)" --strip-components=1 -xzf "$^" && \
	patch -p1 -f -N -r- -d "$(dir $@)" < goruntime-boottime-over-monotonic.diff && \
	touch "$@"'

# GODEBUG madvdontneed=1: freed heap pages go back with MADV_DONTNEED, as they do on GOOS=linux. Go turns that on only
# when GOOS is linux (runtime/runtime1.go parsedebugvars), so GOOS=android keeps MADV_FREE, whose pages stay in the
# app's PSS until the kernel needs the memory (runtime/mem_linux.go sysUnusedOS). The go command refuses madvdontneed
# in //go:debug and go.mod (cmd/go/internal/modload/init.go CheckGodebug: it is not in internal/godebugs.All), and its
# own -X of runtime.godebugDefault comes before these -ldflags while the linker keeps the last -X
# (cmd/go/internal/work/gc.go ld; cmd/link/internal/ld/data.go addstrdata1). So the module's default GODEBUG is read
# with go list, and madvdontneed=1 is appended to it.
$(DESTDIR)/libwg-go.so: export PATH := $(BUILDDIR)/go-$(GO_VERSION)/bin/:$(PATH)
$(DESTDIR)/libwg-go.so: $(BUILDDIR)/go-$(GO_VERSION)/.prepared Makefile go.mod go.sum $(GO_SOURCES)
	godebug="$$(go list -tags linux -buildvcs=false -f '{{.DefaultGODEBUG}}' .)" && \
	go build -tags linux -ldflags="-buildid= -X=runtime.godebugDefault=$${godebug:+$$godebug,}madvdontneed=1" -v -trimpath -buildvcs=false -o "$@" -buildmode c-shared

# go vet and go test -race for dnsintercept and stackdump on the host: the Android GOOS, GOARCH and CGO_* exports above do not
# apply here, so they are unset and the host C compiler is used.
HOST_GO_ENV := env -u GOOS -u GOARCH -u CGO_CFLAGS -u CGO_LDFLAGS CGO_ENABLED=1 CC=cc GOTOOLCHAIN=local

test: export PATH := $(BUILDDIR)/go-$(GO_VERSION)/bin/:$(PATH)
test: $(BUILDDIR)/go-$(GO_VERSION)/.prepared
	$(HOST_GO_ENV) go vet ./dnsintercept/ ./stackdump/
	$(HOST_GO_ENV) go test -race -count=1 ./dnsintercept/ ./stackdump/

.PHONY: default test
.DELETE_ON_ERROR:
