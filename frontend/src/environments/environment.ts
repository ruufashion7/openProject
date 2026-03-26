export const environment = {
  production: false,
  googleMapsApiKey: 'YOUR_GOOGLE_MAPS_API_KEY', // Replace with your actual API key
  /**
   * When true: enable withCredentials on API calls and align with backend security.auth.jwt-cookie-enabled.
   * Production cross-origin: set backend jwt-cookie-secure=true and jwt-cookie-same-site=None.
   */
  useJwtHttpOnlyCookie: false,
  /**
   * Empty: same-origin `/api` (dev proxy or Vercel rewrite). Set to full API origin to bypass a slow/broken rewrite.
   */
  apiBaseUrl: ''
};

