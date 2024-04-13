import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  PutCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "ProductTable";

awsXRay.captureAWS(aws);

export async function handler(event) {

  let body;
  let statusCode = 200;

  try {
    const { category, images } = event;

    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('PutEventInDynamoDb');
    subSegment.addAnnotation('category id', category.id);
    subSegment.addMetadata('category', category);

    body = await dynamo.send(
        new PutCommand({
          TableName: tableName,
          Item: {
            PK: 'CATEGORY#' + category.id,
            SK: category.name,
            name: category.name,
            description: category.description
          },
        })
    );

    body = await dynamo.send(
        new PutCommand({
          TableName: tableName,
          Item: {
            PK: 'CATEGORY#' + category.id,
            SK: category,
            images: images
          },
        })
    );

    subSegment.close();
  } catch (err) {
    statusCode = 400;
    body = err.message;
  } finally {
    body = JSON.stringify(body);
  }
  const headers = {
    "Access-Control-Allow-Headers" : "Content-Type",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "OPTIONS,POST"
  };
  return {statusCode, body, headers};
}