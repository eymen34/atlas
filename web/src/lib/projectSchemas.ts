import { z } from 'zod';

/** Mirror of the backend project-key rule: 2–10 chars, leading uppercase letter. */
export const PROJECT_KEY_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/;

export const createProjectSchema = z.object({
  name: z
    .string()
    .min(1, 'Name is required')
    .max(200, 'Name must be at most 200 characters'),
  key: z
    .string()
    .regex(
      PROJECT_KEY_PATTERN,
      'Key must be 2–10 characters: an uppercase letter followed by uppercase letters or digits'
    ),
  description: z.string().max(1000, 'Description must be at most 1000 characters').optional(),
});

export const updateProjectSchema = z.object({
  name: z
    .string()
    .min(1, 'Name is required')
    .max(200, 'Name must be at most 200 characters'),
  description: z.string().max(1000, 'Description must be at most 1000 characters').optional(),
});

export const addMemberSchema = z.object({
  email: z.string().email('Enter a valid email address'),
  role: z.enum(['MEMBER', 'ADMIN']),
});

export type CreateProjectInput = z.infer<typeof createProjectSchema>;
export type UpdateProjectInput = z.infer<typeof updateProjectSchema>;
export type AddMemberInput = z.infer<typeof addMemberSchema>;
