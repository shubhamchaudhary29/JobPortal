import apiClient from "./helper";
import { apiRoutes } from "./api-routes";
import { roundToGrid } from "../map/geo";

const DEFAULT_RETRY_AFTER_SECONDS = 10;
const PAGE_SIZE = 20;

/** True for a request cancelled through its AbortSignal (axios CanceledError or a DOM AbortError). */
export function isAbortError(error) {
  return error?.code === "ERR_CANCELED" || error?.name === "CanceledError" || error?.name === "AbortError";
}

function mapError(error) {
  if (isAbortError(error)) return error;
  const status = error?.response?.status;
  if (status === 503) {
    const retryAfter = Number.parseInt(error.response.headers?.["retry-after"], 10);
    return Object.assign(new Error("Map data is temporarily unavailable"), {
      kind: "unavailable",
      retryAfterSeconds: Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : DEFAULT_RETRY_AFTER_SECONDS,
    });
  }
  return Object.assign(new Error("Map request failed"), { kind: "error", status });
}

/** Drops absent and blank values so empty filters never reach the URL. */
function compact(params) {
  return Object.fromEntries(Object.entries(params).filter(([, value]) => {
    if (value === undefined || value === null) return false;
    return typeof value !== "string" || value.trim() !== "";
  }));
}

async function get(url, params, signal) {
  try {
    const response = await apiClient.get(url, { params: compact(params), signal });
    return response.data;
  } catch (error) {
    throw mapError(error);
  }
}

/** Every place with jobs plus unplaced counts. Served from memory with an ETag; the browser revalidates it. */
export const getMapSnapshot = ({ signal } = {}) => get(apiRoutes.jobs.mapSnapshot, {}, signal);

/** Slice of jobs at one place: {@code {content, page, size, hasNext, totalHint}}. */
export const getPlaceJobs = (placeId, { q, page = 0, size = PAGE_SIZE, signal } = {}) =>
  get(apiRoutes.jobs.mapPlaceJobs(placeId), { q: q?.trim(), page, size }, signal);

/**
 * Places within a radius and their jobs: {@code {centerLat, centerLng, radiusKm, places, jobs}}. Coordinates are
 * rounded to the 0.05° grid here, so precise positions never leave the browser.
 */
export const getNearbyJobs = ({ lat, lng, radiusKm, q, page = 0, size = PAGE_SIZE, signal } = {}) =>
  get(apiRoutes.jobs.mapNearby, { lat: roundToGrid(lat), lng: roundToGrid(lng), radiusKm, q: q?.trim(), page, size }, signal);

/** Slice of jobs at one pinned office (same shape as a place's jobs). */
export const getOfficeJobs = (officeId, { q, page = 0, size = PAGE_SIZE, signal } = {}) =>
  get(apiRoutes.jobs.mapOfficeJobs(officeId), { q: q?.trim(), page, size }, signal);

/** Jobs without a place: {@code kind} "remote" or "india" (state- and India-wide). */
export const getUnplacedJobs = ({ kind, q, page = 0, size = PAGE_SIZE, signal } = {}) =>
  get(apiRoutes.jobs.mapUnplaced, { kind, q: q?.trim(), page, size }, signal);
