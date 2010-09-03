/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2010 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package org.appcelerator.titanium.proxy;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollInvocation;
import org.appcelerator.kroll.KrollProxy;
import org.appcelerator.kroll.annotations.Kroll;
import org.appcelerator.titanium.TiContext;
import org.appcelerator.titanium.kroll.KrollCallback;
import org.appcelerator.titanium.util.AsyncResult;
import org.appcelerator.titanium.util.Log;
import org.appcelerator.titanium.util.TiAnimationBuilder;
import org.appcelerator.titanium.util.TiConfig;
import org.appcelerator.titanium.view.TiAnimation;
import org.appcelerator.titanium.view.TiUIView;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.View;

@Kroll.proxy
public abstract class TiViewProxy extends KrollProxy implements Handler.Callback
{
	private static final String LCAT = "TiViewProxy";
	private static final boolean DBG = TiConfig.LOGD;

	private static final int MSG_FIRST_ID = KrollProxy.MSG_LAST_ID + 1;

	private static final int MSG_GETVIEW = MSG_FIRST_ID + 100;
	private static final int MSG_ADD_CHILD = MSG_FIRST_ID + 102;
	private static final int MSG_REMOVE_CHILD = MSG_FIRST_ID + 103;
	private static final int MSG_INVOKE_METHOD = MSG_FIRST_ID + 104;
	private static final int MSG_BLUR = MSG_FIRST_ID + 105;
	private static final int MSG_FOCUS = MSG_FIRST_ID + 106;
	private static final int MSG_SHOW = MSG_FIRST_ID + 107;
	private static final int MSG_HIDE = MSG_FIRST_ID + 108;
	private static final int MSG_ANIMATE = MSG_FIRST_ID + 109;
	private static final int MSG_TOIMAGE = MSG_FIRST_ID + 110;
	private static final int MSG_GETSIZE = MSG_FIRST_ID + 111;

	protected static final int MSG_LAST_ID = MSG_FIRST_ID + 999;

	protected ArrayList<TiViewProxy> children;
	protected WeakReference<TiViewProxy> parent;

	private static class InvocationWrapper {
		public String name;
		public Method m;
		public Object target;
		public Object[] args;
	}

	// Ti Properties force using accessors.
	private Double zIndex;

	protected TiUIView view;
	protected TiAnimationBuilder pendingAnimation;

	public TiViewProxy(TiContext tiContext)
	{
		super(tiContext);
	}

	public TiAnimationBuilder getPendingAnimation() {
		return pendingAnimation;
	}

	public void clearAnimation() {
		if (pendingAnimation != null) {
			pendingAnimation = null;
		}
	}

	//This handler callback is tied to the UI thread.
	public boolean handleMessage(Message msg)
	{
		switch(msg.what) {
			case MSG_GETVIEW : {
				AsyncResult result = (AsyncResult) msg.obj;
				result.setResult(handleGetView((Activity) result.getArg()));
				return true;
			}
			case MSG_ADD_CHILD : {
				AsyncResult result = (AsyncResult) msg.obj;
				handleAdd((TiViewProxy) result.getArg());
				result.setResult(null); //Signal added.
				return true;
			}
			case MSG_REMOVE_CHILD : {
				AsyncResult result = (AsyncResult) msg.obj;
				handleRemove((TiViewProxy) result.getArg());
				result.setResult(null); //Signal removed.
				return true;
			}
			case MSG_INVOKE_METHOD : {
				AsyncResult result = (AsyncResult) msg.obj;
				result.setResult(handleInvokeMethod((InvocationWrapper) result.getArg()));
				return true;
			}
			case MSG_BLUR : {
				handleBlur();
				return true;
			}
			case MSG_FOCUS : {
				handleFocus();
				return true;
			}
			case MSG_SHOW : {
				handleShow((KrollDict) msg.obj);
				return true;
			}
			case MSG_HIDE : {
				handleHide((KrollDict) msg.obj);
				return true;
			}
			case MSG_ANIMATE : {
				handleAnimate();
				return true;
			}
			case MSG_TOIMAGE: {
				AsyncResult result = (AsyncResult) msg.obj;
				result.setResult(handleToImage());
				return true;
			}
			case MSG_GETSIZE : {
				AsyncResult result = (AsyncResult) msg.obj;
				KrollDict d = null;
				if (view != null) {
					View v = view.getNativeView();
					if (v != null) {
						d = new KrollDict();
						d.put("width", v.getWidth());
						d.put("height", v.getHeight());
					}
				}
				if (d == null) {
					d = new KrollDict();
					d.put("width", 0);
					d.put("height", 0);
				}

				result.setResult(d);
				return true;
			}
		}
		return super.handleMessage(msg);
	}

	public Context getContext()
	{
		return getTiContext().getActivity();
	}

	@Kroll.getProperty @Kroll.method
	public String getZIndex() {
		return zIndex == null ? (String) null : String.valueOf(zIndex);
	}

	@Kroll.setProperty @Kroll.method
	public void setZIndex(String value) {
		if (value != null && value.trim().length() > 0) {
			zIndex = new Double(value);
		}
	}

	@Kroll.setProperty @Kroll.method
	public KrollDict getSize() {
		AsyncResult result = new AsyncResult(getTiContext().getActivity());
		Message msg = getUIHandler().obtainMessage(MSG_GETSIZE, result);
		msg.sendToTarget();
		return (KrollDict) result.getResult();
	}

	public void clearView() {
		if (view != null) {
			view.release();
		}
		view = null;
	}

	public TiUIView peekView()
	{
		return view;
	}

	public TiUIView getView(Activity activity)
	{
		if (activity == null) {
			activity = getTiContext().getActivity();
		}
		if(getTiContext().isUIThread()) {
			return handleGetView(activity);
		}

		AsyncResult result = new AsyncResult(activity);
		Message msg = getUIHandler().obtainMessage(MSG_GETVIEW, result);
		msg.sendToTarget();
		return (TiUIView) result.getResult();
	}

	protected TiUIView handleGetView(Activity activity)
	{
		if (view == null) {
			if (DBG) {
				Log.i(LCAT, "getView: " + getClass().getSimpleName());
			}

			view = createView(activity);
			realizeViews(activity, view);
			view.registerForTouch();
		}
		return view;
	}

	public void realizeViews(Activity activity, TiUIView view)
	{
		setModelListener(view);

		// Use a copy so bundle can be modified as it passes up the inheritance
		// tree. Allows defaults to be added and keys removed.

		if (children != null) {
			for (TiViewProxy p : children) {
				TiUIView cv = p.getView(activity);
				view.add(cv);
			}
		}
	}

	public void releaseViews() {
		if (view != null) {
			if  (children != null) {
				for(TiViewProxy p : children) {
					p.releaseViews();
				}
			}
			view.release();
			view = null;
		}
	}

	public abstract TiUIView createView(Activity activity);

	@Kroll.method
	public void add(TiViewProxy child) {
		if (children == null) {
			children = new ArrayList<TiViewProxy>();
		}
		if (peekView() != null) {
			if(getTiContext().isUIThread()) {
				handleAdd(child);
				return;
			}

			AsyncResult result = new AsyncResult(child);
			Message msg = getUIHandler().obtainMessage(MSG_ADD_CHILD, result);
			msg.sendToTarget();
			result.getResult(); // We don't care about the result, just synchronizing.
		} else {
			children.add(child);
			child.parent = new WeakReference<TiViewProxy>(this);
		}
		//TODO zOrder
	}

	public void handleAdd(TiViewProxy child)
	{
		children.add(child);
		if (view != null) {
			TiUIView cv = child.getView(getTiContext().getActivity());
			view.add(cv);
			child.parent = new WeakReference<TiViewProxy>(this);
		}
	}

	@Kroll.method
	public void remove(TiViewProxy child)
	{
		if (peekView() != null) {
			if (getTiContext().isUIThread()) {
				handleRemove(child);
				return;
			}

			AsyncResult result = new AsyncResult(child);
			Message msg = getUIHandler().obtainMessage(MSG_REMOVE_CHILD, result);
			msg.sendToTarget();
			result.getResult(); // We don't care about the result, just synchronizing.
		} else {
			if (children != null) {
				children.remove(child);
				if (child.parent != null && child.parent.get() == this) {
					child.parent = null;
				}
			}
		}
	}

	public void handleRemove(TiViewProxy child)
	{
		if (children != null) {
			children.remove(child);
			if (view != null) {
				view.remove(child.peekView());
			}
		}
	}

	@Kroll.method
	public void show(KrollDict options)
	{
		if (getTiContext().isUIThread()) {
			handleShow(options);
		} else {
			getUIHandler().obtainMessage(MSG_SHOW, options).sendToTarget();
		}
	}

	protected void handleShow(KrollDict options) {
		if (view != null) {
			view.show();
		}
	}

	@Kroll.method
	public void hide(KrollDict options) {
		if (getTiContext().isUIThread()) {
			handleHide(options);
		} else {
			getUIHandler().obtainMessage(MSG_HIDE, options).sendToTarget();
		}

	}

	protected void handleHide(KrollDict options) {
		if (view != null) {
			view.hide();
		}
	}

	@Kroll.method
	public void animate(Object arg, @Kroll.argument(optional=true) KrollCallback callback)
	{
		if (arg instanceof KrollDict) {
			KrollDict options = (KrollDict) arg;

			pendingAnimation = new TiAnimationBuilder();
			pendingAnimation.applyOptions(getProperties());
			pendingAnimation.applyOptions(options);
			if (callback != null) {
				pendingAnimation.setCallback(callback);
			}
		} else if (arg instanceof TiAnimation) {
			TiAnimation anim = (TiAnimation) arg;
			pendingAnimation = new TiAnimationBuilder();
			pendingAnimation.applyOptions(getProperties());
			pendingAnimation.applyAnimation(anim);
		} else {
			throw new IllegalArgumentException("Unhandled argument to animate: " + arg.getClass().getSimpleName());
		}
		handlePendingAnimation();
	}

	protected void handlePendingAnimation() {
		if (pendingAnimation != null) {
			if (getTiContext().isUIThread()) {
				handleAnimate();
			} else {
				Message msg = getUIHandler().obtainMessage(MSG_ANIMATE);
				msg.sendToTarget();
			}
		}
	}

	protected void handleAnimate() {
		TiUIView tiv = peekView();

		if (tiv != null) {
			tiv.animate();
		}
	}

	@Kroll.method
	public void blur()
	{
		if (getTiContext().isUIThread()) {
			handleBlur();
		} else {
			getUIHandler().sendEmptyMessage(MSG_BLUR);
		}
	}

	protected void handleBlur() {
		if (view != null) {
			view.blur();
		}
	}
	
	@Kroll.method
	public void focus()
	{
		if (getTiContext().isUIThread()) {
			handleFocus();
		} else {
			getUIHandler().sendEmptyMessage(MSG_FOCUS);
		}
	}
	
	protected void handleFocus() {
		if (view != null) {
			view.focus();
		}
	}

	@Kroll.method
	public KrollDict toImage() {
		if (getTiContext().isUIThread()) {
			return handleToImage();
		} else {
			AsyncResult result = new AsyncResult(getTiContext().getActivity());
			Message msg = getUIHandler().obtainMessage(MSG_TOIMAGE);
			msg.obj = result;
			msg.sendToTarget();
			return (KrollDict) result.getResult();
		}
	}

	protected KrollDict handleToImage() {
		return getView(getTiContext().getActivity()).toImage();
	}

	@Override
	public Object resultForUndefinedMethod(String name, Object[] args)
	{
		if (view != null) {
			Method m = getTiContext().getTiApp().methodFor(view.getClass(), name);
			if (m != null) {
				InvocationWrapper w = new InvocationWrapper();
				w.name = name;
				w.m = m;
				w.target = view;
				w.args = args;

				if (getTiContext().isUIThread()) {
					handleInvokeMethod(w);
				} else {
					AsyncResult result = new AsyncResult(w);
					Message msg = getUIHandler().obtainMessage(MSG_INVOKE_METHOD, result);
					msg.sendToTarget();
					return result.getResult();
				}
			}
		}

		return super.resultForUndefinedMethod(name, args);
	}

	private Object handleInvokeMethod(InvocationWrapper w)
	{
		try {
			return w.m.invoke(w.target, w.args);
		} catch (InvocationTargetException e) {
			Log.e(LCAT, "Error while invoking " + w.name + " on " + view.getClass().getSimpleName(), e);
			// TODO - wrap in a better exception.
			return e;
		} catch (IllegalAccessException e) {
			Log.e(LCAT, "Error while invoking " + w.name + " on " + view.getClass().getSimpleName(), e);
			return e;
		}
	}

	@Override
	public boolean fireEvent(String eventName, KrollDict data) {
		boolean handled = super.fireEvent(eventName, data);

		if (parent != null && parent.get() != null) {
			boolean parentHandled = parent.get().fireEvent(eventName, data);
			handled = handled || parentHandled;
		}
		return handled;
	}
	
	@Kroll.getProperty @Kroll.method
	public TiViewProxy getParent() {
		if (this.parent == null) { return null; }
		return this.parent.get();
	}
	
	public void setParent(TiViewProxy parent) {
		this.parent = new WeakReference<TiViewProxy>(parent);	
	}
	
	@Override
	public TiContext switchContext(TiContext tiContext) {
		TiContext oldContext = super.switchContext(tiContext);
		if (children != null) {
			for (TiViewProxy child : children) {
				child.switchContext(tiContext);
			}
		}
		return oldContext;
	}
	
	@Kroll.getProperty @Kroll.method
	public TiViewProxy[] getChildren() {
		if (children == null) return new TiViewProxy[0];
		
		return children.toArray(new TiViewProxy[children.size()]);
	}
}
