export const environment = {
  production: true,
  googleMapsApiKey: 'YOUR_GOOGLE_MAPS_API_KEY', // Replace with your actual API key for production
  useJwtHttpOnlyCookie: false,
  /**
   * Set to your real API public URL (scheme + host, no path), e.g. https://openproject-api.yourdomain.com
   * so the browser calls the API directly. Keeps CORS (CORS_ALLOWED_ORIGINS) on the API; avoids Vercel proxy timeouts (502/504).
   * Leave '' to use same-origin `/api` (Vercel rewrite must point to a live backend).
   */
  apiBaseUrl: ''
};
