const API = process.env.API || 'http://localhost:8080';

/** Registers or logs in the load accounts and writes perf/.users.json for k6. */
const count = Number(process.env.USERS || 50);
const password = process.env.PASSWORD || 'Load12345!';

async function ensure(index) {
  const email = `load-${index}@perf.kinship.local`;
  const register = await fetch(`${API}/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email,
      password,
      display_name: `Load ${index}`,
      timezone: 'Europe/Berlin',
    }),
  });
  if (register.status === 201) {
    const body = await register.json();
    return { email, password, userId: body.user.id, token: body.access_token };
  }
  const login = await fetch(`${API}/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (login.status !== 200) {
    throw new Error(`cannot prepare ${email}: register=${register.status} login=${login.status}`);
  }
  const body = await login.json();
  return { email, password, userId: body.user.id, token: body.access_token };
}

const users = [];
for (let index = 1; index <= count; index += 1) {
  users.push(await ensure(index));
}
const { writeFileSync } = await import('node:fs');
writeFileSync(new URL('./.users.json', import.meta.url), JSON.stringify(users, null, 2));
console.log(`prepared ${users.length} load accounts`);
