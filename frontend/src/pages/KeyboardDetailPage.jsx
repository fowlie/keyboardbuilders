import React, { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Container, Header, Segment, Button, Icon, Loader, Message } from 'semantic-ui-react';
import { keyboardsApi } from '../api/keyboardsApi';
import { useAuth0 } from '@auth0/auth0-react';
import SafeImage from '../components/SafeImage';

const KeyboardDetailPage = () => {
  const [keyboard, setKeyboard] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const { id } = useParams();
  const { getAccessTokenSilently } = useAuth0();

  useEffect(() => {
    const fetchKeyboard = async () => {
      try {
        setLoading(true);
        const data = await keyboardsApi.getById(id);
        setKeyboard(data);
        setError(null);
      } catch (err) {
        console.error('Error fetching keyboard:', err);
        setError('Failed to load keyboard details. Please try again.');
      } finally {
        setLoading(false);
      }
    };

    fetchKeyboard();
  }, [id]);

  if (loading) {
    return <Loader active>Loading keyboard details...</Loader>;
  }

  if (error) {
    return (
      <Container>
        <Message negative>
          <Message.Header>Error</Message.Header>
          <p>{error}</p>
        </Message>
        <Button as={Link} to="/keyboards">
          Back to Keyboards
        </Button>
      </Container>
    );
  }

  if (!keyboard) {
    return (
      <Container>
        <Message warning>
          <Message.Header>Keyboard Not Found</Message.Header>
          <p>The keyboard you're looking for doesn't exist or has been removed.</p>
        </Message>
        <Button as={Link} to="/keyboards">
          Back to Keyboards
        </Button>
      </Container>
    );
  }

  return (
    <Container>
      <Header as="h1">{keyboard.name}</Header>
      <Segment>
        <p><strong>Type:</strong> {keyboard.split ? 'Split' : 'Non-split'}</p>
        <p><strong>Hotswap:</strong> {keyboard.hotswap ? 'Yes' : 'No'}</p>
        <p><strong>Unibody:</strong> {keyboard.unibody ? 'Yes' : 'No'}</p>
        <p><strong>Splay:</strong> {keyboard.splay ? 'Yes' : 'No'}</p>
        <p><strong>Row Stagger:</strong> {keyboard.rowStagger ? 'Yes' : 'No'}</p>
        <p><strong>Column Stagger:</strong> {keyboard.columnStagger ? 'Yes' : 'No'}</p>
        {keyboard.url && (
          <p>
            <strong>URL:</strong>{' '}
            <a href={keyboard.url} target="_blank" rel="noopener noreferrer">
              {keyboard.url}
            </a>
          </p>
        )}
        {keyboard.user && (
          <p><strong>Owner:</strong> {keyboard.user.name}</p>
        )}
      </Segment>
      <Button as={Link} to="/keyboards" primary>
        <Icon name="arrow left" /> Back to Keyboards
      </Button>
    </Container>
  );
};

export default KeyboardDetailPage; 