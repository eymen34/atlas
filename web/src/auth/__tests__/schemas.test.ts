import { describe, expect, it } from 'vitest';
import { loginSchema, registerSchema } from '../schemas';

describe('auth schemas', () => {
  it('login requires a valid email and a non-empty password', () => {
    expect(loginSchema.safeParse({ email: 'a@b.com', password: 'x' }).success).toBe(true);
    expect(loginSchema.safeParse({ email: 'not-an-email', password: 'x' }).success).toBe(false);
    expect(loginSchema.safeParse({ email: 'a@b.com', password: '' }).success).toBe(false);
  });

  it('register enforces password min 10 and displayName 1..80', () => {
    expect(
      registerSchema.safeParse({ email: 'a@b.com', password: '0123456789', displayName: 'A' }).success
    ).toBe(true);
    expect(
      registerSchema.safeParse({ email: 'a@b.com', password: 'short', displayName: 'A' }).success
    ).toBe(false);
    expect(
      registerSchema.safeParse({ email: 'a@b.com', password: '0123456789', displayName: '' }).success
    ).toBe(false);
    expect(
      registerSchema.safeParse({
        email: 'a@b.com',
        password: '0123456789',
        displayName: 'x'.repeat(81),
      }).success
    ).toBe(false);
  });
});
