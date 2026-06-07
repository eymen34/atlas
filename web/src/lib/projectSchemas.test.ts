import { describe, expect, it } from 'vitest';
import { addMemberSchema, createProjectSchema } from './projectSchemas';

describe('createProjectSchema', () => {
  it('accepts a valid project', () => {
    expect(createProjectSchema.safeParse({ key: 'ATLAS01', name: 'Atlas', description: 'x' }).success).toBe(
      true
    );
  });

  it('rejects a lowercase key', () => {
    expect(createProjectSchema.safeParse({ key: 'atlas01', name: 'Atlas' }).success).toBe(false);
  });

  it('rejects a single-character key (min length 2)', () => {
    expect(createProjectSchema.safeParse({ key: 'A', name: 'Atlas' }).success).toBe(false);
  });

  it('rejects a key longer than 10 characters', () => {
    expect(createProjectSchema.safeParse({ key: 'AAAAAAAAAAA', name: 'Atlas' }).success).toBe(false);
  });

  it('rejects a key that does not start with a letter', () => {
    expect(createProjectSchema.safeParse({ key: '1ABC', name: 'Atlas' }).success).toBe(false);
  });

  it('rejects an empty name and a name over 200 chars', () => {
    expect(createProjectSchema.safeParse({ key: 'AB', name: '' }).success).toBe(false);
    expect(createProjectSchema.safeParse({ key: 'AB', name: 'x'.repeat(201) }).success).toBe(false);
  });

  it('rejects a description over 1000 chars but allows it absent', () => {
    expect(createProjectSchema.safeParse({ key: 'AB', name: 'ok', description: 'y'.repeat(1001) }).success).toBe(
      false
    );
    expect(createProjectSchema.safeParse({ key: 'AB', name: 'ok' }).success).toBe(true);
  });
});

describe('addMemberSchema', () => {
  it('accepts a valid email + role', () => {
    expect(addMemberSchema.safeParse({ email: 'a@b.com', role: 'MEMBER' }).success).toBe(true);
    expect(addMemberSchema.safeParse({ email: 'a@b.com', role: 'ADMIN' }).success).toBe(true);
  });

  it('rejects an invalid email', () => {
    expect(addMemberSchema.safeParse({ email: 'not-an-email', role: 'MEMBER' }).success).toBe(false);
  });

  it('rejects an unknown role', () => {
    expect(addMemberSchema.safeParse({ email: 'a@b.com', role: 'OWNER' }).success).toBe(false);
  });
});
