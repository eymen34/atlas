import { http, HttpResponse } from 'msw';

/** Default happy-path auth handlers; individual tests override via server.use(). */
export const handlers = [
  http.post('/api/auth/login', () =>
    HttpResponse.json({ accessToken: 'access-default', refreshToken: 'refresh-default', expiresIn: 900 })
  ),
  http.post('/api/auth/register', () => new HttpResponse(null, { status: 201 })),
  http.post('/api/auth/refresh', () =>
    HttpResponse.json({ accessToken: 'access-rotated', refreshToken: 'refresh-rotated', expiresIn: 900 })
  ),
  http.post('/api/auth/logout', () => new HttpResponse(null, { status: 204 })),
  http.get('/api/auth/me', () =>
    HttpResponse.json({ id: 'user-1', email: 'alice@example.com', displayName: 'Alice' })
  ),
];
