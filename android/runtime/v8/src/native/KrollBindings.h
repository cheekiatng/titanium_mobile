/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2011-2016 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
#ifndef KROLL_BINDINGS_H
#define KROLL_BINDINGS_H

#include <map>
#include <string>
#include <vector>

#include <v8.h>
#include <jni.h>

namespace titanium {

namespace bindings {
	typedef void (*BindCallback)(v8::Local<v8::Object> exports, v8::Local<v8::Context> context);
	typedef void (*DisposeCallback)();

	struct BindEntry {
		const char *name;
		BindCallback bind;
		DisposeCallback dispose;
	};

} // namespace bindings

typedef bindings::BindEntry* (*LookupFunction)(const char *binding, unsigned int bindingLength);


class KrollBindings
{
private:
	static std::map<std::string, bindings::BindEntry*> externalBindings;
	static std::map<std::string, jobject> externalCommonJsModules;
	static std::map<std::string, jmethodID> commonJsSourceRetrievalMethods;
	static std::vector<LookupFunction> externalLookups;
	static std::map<std::string, bindings::BindEntry*> externalLookupBindings;

public:
	static void initFunctions(v8::Local<v8::Object> exports, v8::Local<v8::Context> context);

	static void initNatives(v8::Local<v8::Object> exports, v8::Local<v8::Context> context);
	static void initTitanium(v8::Local<v8::Object> exports, v8::Local<v8::Context> context);
	static void disposeTitanium();

	static v8::Local<v8::String> getMainSource(v8::Isolate* isolate);

	static void getBinding(const v8::FunctionCallbackInfo<v8::Value>& args);
	static v8::Local<v8::Object> getBinding(v8::Isolate* isolate, v8::Local<v8::String> binding);

	static void getExternalBinding(const v8::FunctionCallbackInfo<v8::Value>& args);
	static void addExternalBinding(const char *name, bindings::BindEntry *binding);
	static void addExternalLookup(LookupFunction lookup);

	static void addExternalCommonJsModule(const char *name, jobject sourceProvider, jmethodID sourceRetrievalMethod);
	static void isExternalCommonJsModule(const v8::FunctionCallbackInfo<v8::Value>& args);
	static void getExternalCommonJsModule(const v8::FunctionCallbackInfo<v8::Value>& args);

	static void dispose(v8::Isolate* isolate);
};

} // namespace titanium

#endif
