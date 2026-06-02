import { z } from 'zod';

export const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

export const registerSchema = z.object({
  email: z.string().email(),
  password: z.string().min(10),
  displayName: z.string().min(1).max(80),
});

/**
 * Backend AuthResponse shape (login/refresh). The backend returns expiresIn
 * (seconds); accessTokenExpiresAt is accepted too in case a future backend adds
 * an absolute timestamp. user is included so login needs no extra /me round-trip.
 */
export const authResponseSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().min(1),
  expiresIn: z.number().optional(),
  accessTokenExpiresAt: z.number().optional(),
  user: z
    .object({ id: z.string(), email: z.string(), displayName: z.string() })
    .optional(),
});

export const userProfileSchema = z.object({
  id: z.string(),
  email: z.string(),
  displayName: z.string(),
});

export type LoginInput = z.infer<typeof loginSchema>;
export type RegisterInput = z.infer<typeof registerSchema>;
export type AuthResponseBody = z.infer<typeof authResponseSchema>;
