/**
 * apiConfig.ts
 * Centralized API & WebSocket Endpoint Resolver for Web, PWA, and Capacitor Android Native APK
 */

// Deployed backend endpoint on Google Cloud Run for Android APK & remote WebView execution
export const DEFAULT_REMOTE_BACKEND = 'https://ais-dev-7ihxvvurzo6eltmndfvicn-245987271404.asia-southeast1.run.app';

/**
 * Robust detector for Capacitor Android Native APK, Android WebView, and local static origins
 */
export function isNativeAndroidPlatform(): boolean {
  if (typeof window === 'undefined') return false;

  // 1. Check custom override in localStorage
  if (localStorage.getItem('iris_force_remote_backend') === 'true') return true;

  // 2. Capacitor runtime object
  if ((window as any).Capacitor?.isNativePlatform?.() || (window as any).Capacitor?.platform === 'android') {
    return true;
  }

  // 3. Custom native Android automation bridges
  if (typeof (window as any).IrisAutomationBridge !== 'undefined' || typeof (window as any).AndroidAutomationBridge !== 'undefined' || typeof (window as any).AndroidBridge !== 'undefined') {
    return true;
  }

  // 4. Capacitor/Cordova custom schemes
  const protocol = window.location.protocol;
  if (protocol === 'capacitor:' || protocol === 'file:' || protocol === 'ionic:') {
    return true;
  }

  // 5. Capacitor Android default WebView origin (https://localhost or http://localhost without dev server ports 3000/5173)
  const hostname = window.location.hostname;
  const port = window.location.port;
  if (hostname === 'localhost' && port !== '3000' && port !== '5173') {
    return true;
  }

  // 6. User Agent contains Android and WebView indicators
  const ua = navigator.userAgent;
  if (/Android/i.test(ua) && (/wv|Version\/[0-9.]+/i.test(ua) || /Capacitor/i.test(ua))) {
    return true;
  }

  return false;
}

export function getBackendBaseUrl(): string {
  if (typeof window === 'undefined') return '';

  // 1. User custom setting (stored in localStorage)
  const customUrl = localStorage.getItem('iris_backend_server_url')?.trim();
  if (customUrl) {
    return customUrl.replace(/\/+$/, '');
  }

  // 2. Environment variable
  const envUrl = (import.meta as any).env?.VITE_BACKEND_URL?.trim();
  if (envUrl) {
    return envUrl.replace(/\/+$/, '');
  }

  // 3. Android APK / Native WebView check - route directly to live backend
  if (isNativeAndroidPlatform()) {
    return DEFAULT_REMOTE_BACKEND;
  }

  // 4. Default web browser: use current origin
  return window.location.origin;
}

export function getApiUrl(path: string): string {
  const cleanPath = path.startsWith('/') ? path : `/${path}`;
  const base = getBackendBaseUrl();

  // If in web browser environment where backend is same origin, relative path works
  if (typeof window !== 'undefined' && !isNativeAndroidPlatform() && base === window.location.origin) {
    return cleanPath;
  }

  return `${base}${cleanPath}`;
}

export function getWsUrl(path: string = '/api/live'): string {
  const cleanPath = path.startsWith('/') ? path : `/${path}`;
  const base = getBackendBaseUrl();

  let wsBase = '';
  if (base.startsWith('https://')) {
    wsBase = base.replace('https://', 'wss://');
  } else if (base.startsWith('http://')) {
    wsBase = base.replace('http://', 'ws://');
  } else if (typeof window !== 'undefined') {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    wsBase = `${protocol}//${window.location.host}`;
  } else {
    wsBase = 'wss://localhost:3000';
  }

  return `${wsBase}${cleanPath}`;
}
