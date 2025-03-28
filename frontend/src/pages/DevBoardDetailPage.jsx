import React, { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Container, Header, Segment, Button, Icon, Loader, Message } from 'semantic-ui-react';
import { devBoardsApi } from '../api/devBoardsApi';
import { useAuth0 } from '@auth0/auth0-react';

const DevBoardDetailPage = () => {
  const [devBoard, setDevBoard] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const { id } = useParams();
  const { getAccessTokenSilently } = useAuth0();

  useEffect(() => {
    const fetchDevBoard = async () => {
      try {
        setLoading(true);
        const data = await devBoardsApi.getById(id);
        console.log('DevBoard data:', data);
        setDevBoard(data);
        setError(null);
      } catch (err) {
        console.error('Error fetching dev board:', err);
        setError('Failed to load dev board details. Please try again.');
      } finally {
        setLoading(false);
      }
    };

    fetchDevBoard();
  }, [id]);

  if (loading) {
    return <Loader active>Loading dev board details...</Loader>;
  }

  if (error) {
    return (
      <Container>
        <Message negative>
          <Message.Header>Error</Message.Header>
          <p>{error}</p>
        </Message>
        <Button as={Link} to="/devboards">
          Back to Dev Boards
        </Button>
      </Container>
    );
  }

  if (!devBoard) {
    return (
      <Container>
        <Message warning>
          <Message.Header>Dev Board Not Found</Message.Header>
          <p>The dev board you're looking for doesn't exist or has been removed.</p>
        </Message>
        <Button as={Link} to="/devboards">
          Back to Dev Boards
        </Button>
      </Container>
    );
  }

  return (
    <Container>
      <Header as="h1">{devBoard.name}</Header>
      <Segment>
        <p><strong>Wireless:</strong> {devBoard.wireless ? 'Yes' : 'No'}</p>
        
        {devBoard.controller && (
          <div>
            <Header as="h3">Controller</Header>
            <p><strong>Name:</strong> {devBoard.controller.name}</p>
            {devBoard.controller.manufacturer && <p><strong>Manufacturer:</strong> {devBoard.controller.manufacturer}</p>}
            {devBoard.controller.chipset && <p><strong>Chipset:</strong> {devBoard.controller.chipset}</p>}
            {devBoard.controller.url && (
              <p>
                <strong>URL:</strong>{' '}
                <a href={devBoard.controller.url} target="_blank" rel="noopener noreferrer">
                  {devBoard.controller.url}
                </a>
              </p>
            )}
          </div>
        )}
        
        {devBoard.url && (
          <p>
            <strong>URL:</strong>{' '}
            <a href={devBoard.url} target="_blank" rel="noopener noreferrer">
              {devBoard.url}
            </a>
          </p>
        )}
        {devBoard.user && (
          <p><strong>Owner:</strong> {devBoard.user.name}</p>
        )}
      </Segment>
      <Button as={Link} to="/devboards" primary>
        <Icon name="arrow left" /> Back to Dev Boards
      </Button>
    </Container>
  );
};

export default DevBoardDetailPage; 