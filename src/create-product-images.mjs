import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  PutCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "OnlineShop";

awsXRay.captureAWS(aws);

export async function handler(event) {
  console.log('Received Step Functions event:', JSON.stringify(event, null, 2));

  const { productId, productImage, resizedImageUrls } = event.Input;

  let body;
  let statusCode = 200;
  try {
    let requestJSON = JSON.parse(event.body);

    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('PutEventInDynamoDb');
    subSegment.addAnnotation('product id', requestJSON.id);
    subSegment.addMetadata('product', requestJSON);

    body = await dynamo.send(
        new PutCommand({
          TableName: tableName,
          Item: {
            PK: 'PRODUCT#' + productId,
            SK: productImage,
            imageUrls: resizedImageUrls
          },
        })
    );
    subSegment.close();
  } catch (err) {
    statusCode = 400;
    body = err.message;
  }
  const headers = {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST"
  };
  return {statusCode, body, headers};
}