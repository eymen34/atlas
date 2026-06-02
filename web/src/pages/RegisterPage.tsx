import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router';
import { registerSchema, type RegisterInput } from '@/auth/schemas';
import { ApiHttpError, useRegister } from '@/auth/useAuthMutations';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

export function RegisterPage() {
  const registerMutation = useRegister();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterInput>({
    resolver: zodResolver(registerSchema),
    defaultValues: { email: '', password: '', displayName: '' },
  });

  const onSubmit = handleSubmit((values) => {
    registerMutation.mutate(values, {
      onError: (err) => {
        const status = err instanceof ApiHttpError ? err.status : 0;
        if (status === 409) {
          // Field-level, co-located with the email input — never a root error.
          setError('email', {
            type: 'server',
            message: 'An account with this email already exists',
          });
        } else if (status === 400) {
          setError('root', { message: 'Please check your details and try again.' });
        } else {
          setError('root', { message: 'Something went wrong. Please try again.' });
        }
      },
    });
  });

  return (
    <div className="mx-auto mt-16 max-w-md px-4">
      <Card>
        <CardHeader>
          <CardTitle>Create your account</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} noValidate className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                aria-invalid={errors.email ? 'true' : undefined}
                {...register('email')}
              />
              {errors.email && (
                <p role="alert" className="text-sm text-destructive">
                  {errors.email.message}
                </p>
              )}
            </div>
            <div className="space-y-1">
              <Label htmlFor="displayName">Display name</Label>
              <Input
                id="displayName"
                type="text"
                autoComplete="name"
                aria-invalid={errors.displayName ? 'true' : undefined}
                {...register('displayName')}
              />
              {errors.displayName && (
                <p role="alert" className="text-sm text-destructive">
                  {errors.displayName.message}
                </p>
              )}
            </div>
            <div className="space-y-1">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                autoComplete="new-password"
                aria-invalid={errors.password ? 'true' : undefined}
                {...register('password')}
              />
              {errors.password && (
                <p role="alert" className="text-sm text-destructive">
                  {errors.password.message}
                </p>
              )}
            </div>
            {errors.root && (
              <p role="alert" data-testid="form-error" className="text-sm text-destructive">
                {errors.root.message}
              </p>
            )}
            <Button
              type="submit"
              className="w-full"
              disabled={isSubmitting || registerMutation.isPending}
            >
              Create account
            </Button>
          </form>
          <p className="mt-4 text-sm text-muted-foreground">
            Already have an account?{' '}
            <Link to="/login" className="underline">
              Sign in
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
