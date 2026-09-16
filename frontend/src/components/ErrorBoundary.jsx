import { Component } from 'react';

/**
 * Catches render-time errors in the component tree below it. Without this,
 * an unexpected error (e.g. a malformed API response reaching a component
 * that assumes a shape) unmounts the whole app to a blank white screen with
 * no indication anything went wrong.
 */
export default class ErrorBoundary extends Component {
  state = { error: null };

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('Madison Route Planning crashed:', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="panel" style={{ margin: '1.5rem 2rem', maxWidth: 640 }}>
          <h2>Something went wrong</h2>
          <p className="status error">{this.state.error.message}</p>
          <button type="button" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
