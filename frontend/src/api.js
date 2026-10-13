export class ApiError extends Error {
  constructor(status, body = {}) { super(body.code ?? 'REQUEST_FAILED'); this.status = status; this.code = body.code; this.fieldErrors = body.fieldErrors ?? []; this.requestId = body.requestId; }
}

export class ApiClient {
  constructor({fetcher = globalThis.fetch.bind(globalThis), onUnauthorized = () => {}} = {}) {
    this.fetcher = fetcher;
    this.onUnauthorized = onUnauthorized;
  }
  async request(path, {method='GET', body, headers={}, signal, anonymous=false} = {}) {
    const writing = !['GET','HEAD'].includes(method);
    const options = {method, credentials:'same-origin', cache:'no-store', headers:{Accept:'application/json',...headers}, signal};
    if (body !== undefined) { options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body); }
    let response;
    try { response = await this.fetcher(`/api/v1${path}`, options); }
    catch (error) { if (error.name === 'AbortError') throw error; error.network = true; error.writing = writing; throw error; }
    const json = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
      const error = new ApiError(response.status, json ?? {});
      if (response.status === 401 && !anonymous) this.onUnauthorized();
      throw error;
    }
    return json;
  }
  get(path, options) { return this.request(path, options); }
  async mutate(path, {method='POST', body, headers={}} = {}) {
    // A token from the current session is fetched every time; unsafe requests are never auto-retried.
    const csrf = await this.get('/auth/csrf');
    return this.request(path, {method,body,headers:{...headers,[csrf.headerName]:csrf.token}});
  }
}
