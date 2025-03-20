import React from 'react';
import { Container, Header, Card, Image, Button } from 'semantic-ui-react';
import { useAuth0 } from '@auth0/auth0-react';
import { Loader } from 'semantic-ui-react';
const ProfilePage = () => {
    const { user, isAuthenticated, isLoading } = useAuth0();

    if (isLoading) {
        return <Loader active inline='centered' />;
    }

    return (
        <Container>
            <Header as='h1'>Profile</Header>
            <Card>
                <Image src={user.picture} />
                <Card.Content>
                    <Card.Header>{user.name}</Card.Header>
                    <Card.Meta>{user.email}</Card.Meta>
                </Card.Content>
            </Card>
        </Container>
    );
};

export default ProfilePage;
